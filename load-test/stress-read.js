// S5: stress test for the public read workload (Phase 2, docs/phase-2/ke-hoach.md section 4).
// Open model: k6 keeps the target arrival rate even when the API slows down, so the saturation
// point shows up as rising latency and missing requests instead of being hidden by slower VUs.
import exec from 'k6/execution';
import { fail } from 'k6';
import { BASE, fixture, request } from './lib/common.js';

const saved = fixture();
const STAGES = (__ENV.STAGES || '250,500,1000,1500,2000,3000').split(',').map(Number);
const STAGE_SECONDS = Number(__ENV.STAGE_SECONDS || 60);
const RAMP_SECONDS = Number(__ENV.RAMP_SECONDS || 10);
const WARMUP = Number(__ENV.WARMUP_SECONDS || 30);
const MAX_VUS = Number(__ENV.MAX_VUS || 600);
const READ_P95_MS = Number(__ENV.READ_P95_MS || 500);
const MAX_ERROR_RATE = Number(__ENV.MAX_ERROR_RATE || 0.01);
const MAX_MISSING_RATE = Number(__ENV.MAX_MISSING_RATE || 0.01);

if (!STAGES.length || STAGES.some(rate => !Number.isInteger(rate) || rate < 1)
        || STAGES.some((rate, index) => index > 0 && rate <= STAGES[index - 1]))
    throw new Error('STAGES must be increasing positive integers, for example 250,500,1000');
if (!(STAGE_SECONDS > 0) || !(RAMP_SECONDS >= 0) || !(WARMUP >= 0) || !(MAX_VUS >= 1))
    throw new Error('Invalid STAGE_SECONDS, RAMP_SECONDS, WARMUP_SECONDS or MAX_VUS');

const stageTag = rate => 'r' + rate;

// Measure schedule: hold STAGES[0], then for every next stage ramp for RAMP_SECONDS and hold.
// Requests sent while ramping are tagged stage=ramp and excluded from per-stage numbers.
function measureStages() {
    const stages = [{ target: STAGES[0], duration: STAGE_SECONDS + 's' }];
    for (const rate of STAGES.slice(1)) {
        if (RAMP_SECONDS > 0) stages.push({ target: rate, duration: RAMP_SECONDS + 's' });
        stages.push({ target: rate, duration: STAGE_SECONDS + 's' });
    }
    return stages;
}
function stageAt(measureElapsedSeconds) {
    let start = 0;
    for (let index = 0; index < STAGES.length; index++) {
        if (index > 0) {
            if (measureElapsedSeconds < start + RAMP_SECONDS) return 'ramp';
            start += RAMP_SECONDS;
        }
        if (measureElapsedSeconds < start + STAGE_SECONDS) return stageTag(STAGES[index]);
        start += STAGE_SECONDS;
    }
    return 'ramp';
}

const scenarios = {};
if (WARMUP > 0) scenarios.warmup = { executor: 'constant-arrival-rate', rate: STAGES[0], timeUnit: '1s',
    duration: WARMUP + 's', preAllocatedVUs: Math.min(MAX_VUS, 50), maxVUs: MAX_VUS, exec: 'traffic', gracefulStop: '0s' };
scenarios.measure = { executor: 'ramping-arrival-rate', startRate: STAGES[0], timeUnit: '1s',
    stages: measureStages(), startTime: WARMUP + 's', preAllocatedVUs: Math.min(MAX_VUS, 100), maxVUs: MAX_VUS,
    exec: 'traffic', gracefulStop: '5s' };

// k6 only reports tagged sub-metrics that a threshold references. These thresholds always pass:
// a stress test is expected to exceed the SLO at high stages, and handleSummary evaluates each stage.
const thresholds = {};
for (const rate of STAGES) {
    const tag = '{stage:' + stageTag(rate) + '}';
    thresholds['measured_requests' + tag] = ['count>=0'];
    thresholds['measured_read_latency' + tag] = ['max>=0'];
    thresholds['measured_technical_error' + tag] = ['rate>=0'];
    thresholds['measured_unexpected' + tag] = ['count>=0'];
}
export const options = { scenarios: scenarios, thresholds: thresholds, discardResponseBodies: true,
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'] };

export function setup() {
    let movieIds = saved && saved.movieIds;
    let showtimes = saved && saved.showtimes;
    if (!showtimes && saved && saved.showtimeId)
        showtimes = [{ id: saved.showtimeId, movieId: saved.movieIds[0], date: saved.showtimeDate }];
    if (!movieIds || !movieIds.length || !showtimes || !showtimes.length)
        fail('Use fixtures from seed.mjs with SEED_SCOPE=all or large (needs movieIds and showtimes)');
    const sample = showtimes[0];
    const seats = request('GET', '/api/v1/showtimes/' + sample.id + '/seats', null, null, [200], 'setup', 'setup');
    if (seats.status !== 200) fail('Seat endpoint is not reachable for the fixture showtime');
    // Response bodies are discarded to keep k6 cheap at high rates, so setup checks status only.
    const movies = request('GET', '/api/v1/movies?page=1&pageSize=1', null, null, [200], 'setup', 'setup');
    if (movies.status !== 200) fail('Movie list is not reachable');
    return { showtimes: showtimes.map(value => ({ id: value.id, movieId: value.movieId, date: value.date })) };
}

// Same read mix as S3 booking.js (7:5:5 of its 20-request cycle): about 41% movie list,
// 29.5% showtime list, 29.5% seat status. The order is randomised per request.
export function traffic(context) {
    if (exec.scenario.name === 'measure') {
        const elapsed = (exec.instance.currentTestRunDuration - WARMUP * 1000) / 1000;
        exec.vu.metrics.tags.stage = stageAt(elapsed);
    } else exec.vu.metrics.tags.stage = 'warmup';
    const showtime = context.showtimes[Math.floor(Math.random() * context.showtimes.length)];
    const pick = Math.random() * 17;
    if (pick < 7) request('GET', '/api/v1/movies?page=1&pageSize=20', null, null);
    else if (pick < 12) request('GET', '/api/v1/showtimes?movieId=' + showtime.movieId +
        '&date=' + encodeURIComponent(showtime.date), null, null);
    else request('GET', '/api/v1/showtimes/' + showtime.id + '/seats', null, null);
}

export function handleSummary(result) {
    const metric = key => result.metrics[key] ? result.metrics[key].values : {};
    const stages = STAGES.map(rate => {
        const tag = '{stage:' + stageTag(rate) + '}';
        const count = metric('measured_requests' + tag).count || 0;
        const latency = metric('measured_read_latency' + tag);
        const target = rate * STAGE_SECONDS;
        const row = {
            targetRate: rate,
            achievedRate: count / STAGE_SECONDS,
            requests: count,
            missingRate: Math.max(0, 1 - count / target),
            p50Ms: latency.med === undefined ? null : latency.med,
            p95Ms: latency['p(95)'] === undefined ? null : latency['p(95)'],
            p99Ms: latency['p(99)'] === undefined ? null : latency['p(99)'],
            technicalErrorRate: metric('measured_technical_error' + tag).rate || 0,
            unexpectedStatuses: metric('measured_unexpected' + tag).count || 0
        };
        row.sustainable = count > 0 && row.p95Ms !== null && row.p95Ms < READ_P95_MS
            && row.technicalErrorRate < MAX_ERROR_RATE && row.missingRate < MAX_MISSING_RATE
            && row.unexpectedStatuses === 0;
        return row;
    });
    // Highest stage that holds the SLO while every lower stage also held it.
    let maxSustainableRate = null;
    for (const stage of stages) {
        if (!stage.sustainable) break;
        maxSustainableRate = stage.targetRate;
    }
    const metadata = { script: 'stress-read', measuredAt: new Date().toISOString(), baseUrl: BASE,
        gitCommit: __ENV.GIT_COMMIT || 'not recorded',
        model: 'open model; ramping-arrival-rate; one public read request per iteration; no think time',
        mix: { movieList: 7 / 17, showtimeList: 5 / 17, seatStatus: 5 / 17 },
        stages: STAGES, stageSeconds: STAGE_SECONDS, rampSeconds: RAMP_SECONDS, warmupSeconds: WARMUP, maxVUs: MAX_VUS,
        slo: { readP95Ms: READ_P95_MS, maxTechnicalErrorRate: MAX_ERROR_RATE, maxMissingRate: MAX_MISSING_RATE },
        droppedIterationsTotal: metric('dropped_iterations').count || 0,
        maxSustainableRate: maxSustainableRate,
        note: 'missingRate = 1 - requests / (targetRate x stageSeconds); ramp periods are excluded',
        resources: 'collect separately; not included in k6 metrics' };
    const fmt = value => value === null ? '-' : value.toFixed(2);
    const lines = ['stress-read: max sustainable rate = ' + (maxSustainableRate === null ? 'none' : maxSustainableRate + ' req/s'),
        'target  achieved  p50ms  p95ms  p99ms  errors%  missing%  ok'];
    for (const stage of stages) lines.push([stage.targetRate, fmt(stage.achievedRate), fmt(stage.p50Ms), fmt(stage.p95Ms),
        fmt(stage.p99Ms), (stage.technicalErrorRate * 100).toFixed(2), (stage.missingRate * 100).toFixed(2),
        stage.sustainable ? 'yes' : 'no'].join('  '));
    const files = { stdout: lines.join('\n') + '\n' };
    files[__ENV.SUMMARY_PATH || 'load-test/results/stress-read-' + Date.now() + '.json'] =
        JSON.stringify({ metadata: metadata, stages: stages, summary: result }, null, 2);
    return files;
}
