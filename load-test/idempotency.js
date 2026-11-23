import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';
import { VUS, fixture, request, parse, data, cleanupUsers, summary, uuidv4 } from './lib/common.js';

const saved = fixture(true);
const created = new Counter('idempotency_created');
const replayed = new Counter('idempotency_replayed');
const unexpected = new Counter('unexpected_idempotency_outcomes');

export const options = {
    scenarios: {
        measure: {
            executor: 'per-vu-iterations',
            vus: VUS,
            iterations: 1,
            maxDuration: '30s'
        }
    },
    thresholds: {
        idempotency_created: ['count==1'],
        idempotency_replayed: ['count==' + (VUS - 1)],
        unexpected_idempotency_outcomes: ['count==0'],
        checks: ['rate==1']
    },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max']
};

export function setup() {
    if (!saved.loadUsers || saved.loadUsers.length === 0 || !saved.showtimeId) {
        fail('Idempotency fixtures require a load user and showtime');
    }
    const user = saved.loadUsers[0];
    if (!user.userId || !user.token || !user.seatId) {
        fail('The idempotency fixture user is incomplete');
    }
    const profile = data(request('GET', '/api/v1/users/me', null, user.token, [200], 'setup', 'setup'));
    if (!profile || profile.id !== user.userId) {
        fail('Fixture user does not match its token');
    }
    cleanupUsers([user]);
    return {
        user: user,
        showtimeId: saved.showtimeId,
        key: __ENV.IDEMPOTENCY_KEY || uuidv4()
    };
}

export default function (context) {
    const response = request(
        'POST',
        '/api/v1/bookings',
        { showtimeId: context.showtimeId, seatIds: [context.user.seatId] },
        context.user.token,
        [200, 201],
        'booking',
        null,
        { 'Idempotency-Key': context.key }
    );
    const body = parse(response);
    const bookingId = body && body.data && body.data.bookingId;
    const isCreated = response.status === 201 && !!bookingId;
    const isReplayed = response.status === 200 && !!bookingId
        && response.headers['Idempotency-Replayed'] === 'true';

    created.add(isCreated ? 1 : 0);
    replayed.add(isReplayed ? 1 : 0);
    unexpected.add(isCreated || isReplayed ? 0 : 1);
    check(response, {
        'One create or documented replay': () => !!(isCreated || isReplayed),
        'Every successful response has a booking ID': () => !!bookingId
    });
}

export function teardown(context) {
    cleanupUsers([context.user]);
}

export function handleSummary(result) {
    return summary('idempotency', result);
}
