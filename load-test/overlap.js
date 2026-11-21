import exec from 'k6/execution';
import { check, fail, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { VUS, fixture, request, parse, data, cleanupUsers, summary } from './lib/common.js';

const saved = fixture(true);
const durationSeconds = Number(__ENV.DURATION_SECONDS || 60);

const overlapSuccess = new Counter('overlap_success_bookings');
const overlapConflicts = new Counter('overlap_seat_conflicts');
const unexpectedOutcomes = new Counter('unexpected_overlap_outcomes');
const deadlockErrors = new Counter('overlap_deadlocks');

export const options = {
    scenarios: {
        measure: {
            executor: 'constant-vus',
            vus: VUS,
            duration: durationSeconds + 's',
            gracefulStop: '5s'
        }
    },
    thresholds: {
        overlap_deadlocks: ['count==0'],
        unexpected_overlap_outcomes: ['count==0'],
        checks: ['rate==1']
    },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max']
};

export function setup() {
    if (!saved.loadUsers || saved.loadUsers.length < VUS || !saved.showtimeId) {
        fail('Fixtures require at least VUS loadUsers and a valid showtimeId');
    }

    const testUsers = saved.loadUsers.slice(0, VUS);
    if (new Set(testUsers.map(u => u.userId)).size !== VUS) {
        fail('Load users must have distinct user IDs');
    }

    // Verify token validity for all participating users
    for (const user of testUsers) {
        const profile = data(request('GET', '/api/v1/users/me', null, user.token, [200], 'setup', 'setup'));
        if (!profile || profile.id !== user.userId) {
            fail('Fixture user does not match token profile: ' + user.userId);
        }
    }

    // Clean up any lingering bookings from prior runs
    cleanupUsers(testUsers);

    // Fetch the physical seat layout of the target showtime to form overlapping subsets
    const seatsResponse = request('GET', '/api/v1/showtimes/' + saved.showtimeId + '/seats', null, null, [200], 'setup', 'setup');
    const seatsList = parse(seatsResponse);
    const availableSeats = Array.isArray(seatsList) ? seatsList : (seatsList && seatsList.data);

    if (!Array.isArray(availableSeats) || availableSeats.length < 4) {
        fail('Target showtime must have at least 4 seats to perform overlapping bookings');
    }

    const seatIds = availableSeats.map(s => s.id || s.seatId);
    return {
        showtimeId: saved.showtimeId,
        seatIds: seatIds
    };
}

/**
 * Shuffles an array in place (Fisher-Yates) to deliberately send seats in random order.
 * This tests whether the server orders locks properly or encounters deadlocks (Hypothesis H4).
 */
function shuffle(array) {
    const copy = array.slice();
    for (let i = copy.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        const temp = copy[i];
        copy[i] = copy[j];
        copy[j] = temp;
    }
    return copy;
}

// Track currently active booking per VU to cancel and free seats for subsequent iterations
const activeBookings = {};

export default function (context) {
    const vuIndex = (exec.vu.idInTest - 1) % VUS;
    const user = saved.loadUsers[vuIndex];
    const iteration = exec.scenario.iterationInTest;
    const allSeats = context.seatIds;

    // Pick 2 to 4 adjacent/overlapping seats based on VU index and iteration
    const groupSize = 2 + (iteration % 3); // cycles through 2, 3, 4 seats
    const maxStart = Math.max(0, allSeats.length - groupSize);
    // Uniform random start index in [0, maxStart] inclusive, covering from index 0 to the very last seat
    const startIndex = Math.floor(Math.random() * (maxStart + 1));
    const chosenSeats = allSeats.slice(startIndex, startIndex + groupSize);

    // Shuffle seat order to verify database lock acquisition order
    const payloadSeatIds = shuffle(chosenSeats);

    // Cancel previously held booking (if any) before attempting a new booking
    const prevBookingId = activeBookings[user.userId];
    if (prevBookingId) {
        const cancelRes = request('DELETE', '/api/v1/bookings/' + prevBookingId, null, user.token, [204, 404, 409], 'cleanup');
        delete activeBookings[user.userId];
    }

    // Attempt overlapping booking (kind='booking' ensures latency is tracked in measured_booking_latency)
    const response = request(
        'POST',
        '/api/v1/bookings',
        {
            showtimeId: context.showtimeId,
            seatIds: payloadSeatIds
        },
        user.token,
        [201, 409],
        'booking'
    );

    const body = parse(response);
    const won = response.status === 201 && body && body.data && body.data.bookingId;
    const conflict = response.status === 409 && body && body.error && body.error.code === 'SEAT_ALREADY_BOOKED';
    // Deadlock is identified by Postgres SQLSTATE 40P01 or explicit deadlock error, not arbitrary 500s
    const isDeadlock = (response.status === 409 || response.status === 500) && body && body.error &&
        (body.error.code === 'DEADLOCK' ||
         (typeof body.error.message === 'string' && body.error.message.toLowerCase().includes('deadlock')));

    if (won) {
        overlapSuccess.add(1);
        activeBookings[user.userId] = body.data.bookingId;
    } else if (conflict) {
        overlapConflicts.add(1);
    } else {
        unexpectedOutcomes.add(1);
        if (isDeadlock) {
            deadlockErrors.add(1);
        }
    }

    check(response, {
        'Outcome is either 201 (booked) or 409 (seat conflict)': () => !!(won || conflict),
        'No deadlock or 5xx server error': () => !isDeadlock && response.status < 500
    });

    sleep(0.1); // Think time 100ms
}

export function teardown() {
    cleanupUsers(saved.loadUsers.slice(0, VUS));
}

export function handleSummary(result) {
    return summary('overlap', result);
}
