import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

// SEED_SCOPE: movie (default) | all (S1/S3 fixtures) | large (everything in "all" plus the S7 dataset for E4/S5).
const base=(process.env.BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/,'');
const scope=process.env.SEED_SCOPE || 'movie';
if(!['movie','all','large'].includes(scope)) throw new Error('SEED_SCOPE must be movie, all or large');
const full=scope==='all' || scope==='large';
const count=Number(process.env.MOVIE_COUNT || 1000);
const deletedCount=Number(process.env.DELETED_COUNT || 50);
const vus=Number(process.env.VUS || 50);
const password=process.env.LOAD_PASSWORD || 'load-test-123';
const prefix='load-' + Date.now().toString(36);
if (!Number.isInteger(count) || count < 1 || deletedCount < 0 || deletedCount >= count || !Number.isInteger(vus) || vus < 1 || vus > 50)
    throw new Error('Use MOVIE_COUNT>DELETED_COUNT>=0 and VUS between 1 and 50');
async function api(method, route, body, token, expected=200, headers={}) {
    const response=await fetch(base+route,{method,headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{}),...headers},
        body:body===undefined?undefined:JSON.stringify(body)});
    const text=await response.text();
    const result=text?JSON.parse(text):null;
    if(response.status!==expected) {
        const error=new Error(method+' '+route+' returned '+response.status+(result && result.error ? ' '+result.error.code : ''));
        error.status=response.status;
        throw error;
    }
    return result && (result.data===undefined?result:result.data);
}
async function login(username) { return (await api('POST','/api/v1/auth/login',{username,password})).accessToken; }
let admin=process.env.ADMIN_TOKEN;
if(!admin) {
    if(!process.env.ADMIN_USERNAME || !process.env.ADMIN_PASSWORD) throw new Error('Set ADMIN_TOKEN or ADMIN_USERNAME and ADMIN_PASSWORD');
    admin=(await api('POST','/api/v1/auth/login',{username:process.env.ADMIN_USERNAME,password:process.env.ADMIN_PASSWORD})).accessToken;
}
const saved={baseUrl:base,createdAt:new Date().toISOString(),scope,movieIds:[],createdCount:count,deletedCount};
fs.mkdirSync('load-test/results',{recursive:true});
const output=path.resolve(process.env.FIXTURES || 'load-test/fixtures.local.json');
function persist(){fs.writeFileSync(output,JSON.stringify(saved,null,2)+'\n');}
persist();
for(let index=0;index<count;index++) {
    const movie=await api('POST','/api/v1/admin/movies',{title:prefix+' movie '+index,description:'Dataset for repeatable load tests',
        durationMinutes:120,ageRating:'P',releaseDate:'2026-10-01',status:'NOW_SHOWING'},admin,201);
    if(index<deletedCount) await api('DELETE','/api/v1/admin/movies/'+movie.id,undefined,admin,204);
    else saved.movieIds.push(movie.id);
    persist();
}
if(full) {
    const room=await api('POST','/api/v1/admin/auditoriums',{name:prefix,seatLayout:[{row:'A',from:1,to:2*vus+9,type:'STANDARD'}]},admin,201);
    const start=new Date(Date.now()+48*3600000);
    const showtime=await api('POST','/api/v1/admin/showtimes',{movieId:saved.movieIds[0],auditoriumId:room.id,startsAt:start.toISOString(),basePrice:90000},admin,201);
    const contention=await api('POST','/api/v1/admin/showtimes',{movieId:saved.movieIds[0],auditoriumId:room.id,
        startsAt:new Date(start.getTime()+24*3600000).toISOString(),basePrice:90000},admin,201);
    saved.showtimeId=showtime.id;saved.contentionShowtimeId=contention.id;
    saved.showtimeDate=start.toISOString().slice(0,10);
    const seats=[...room.seats].sort((a,b)=>a.number-b.number);
    if(seats.length<2*vus+9) throw new Error('Room response does not contain enough seats');
    saved.contentionSeatIds=seats.slice(2*vus,2*vus+9).map(seat=>seat.id);
    saved.loadUsers=[];saved.contenders=[];
    for(let index=0;index<3*vus;index++) {
        const username=prefix+'-'+index;
        const user=await api('POST','/api/v1/auth/register',{username,password},undefined,201);
        const entry={userId:user.id,username,token:await login(username)};
        if(index<2*vus) saved.loadUsers.push({...entry,seatId:seats[index].id});else saved.contenders.push(entry);
        persist();
    }
}
if(scope==='large') await seedLarge();
persist();
console.log('Created '+count+' movies; '+deletedCount+' soft-deleted. Fixtures: '+output);
console.log('Fixtures contain tokens when scope=all or large. Regenerate or refresh them after login tokens expire.');

// ---------------------------------------------------------------------------------------------
// S7 large dataset (docs/phase-2/ke-hoach.md, E4). Bookings go through the public API so every
// row obeys the same business rules, constraints and soft-cancel semantics as real traffic.
async function seedLarge() {
    const num=(name,fallback)=>{const value=Number(process.env[name] ?? fallback);if(!Number.isFinite(value)) throw new Error(name+' must be a number');return value;};
    const cfg={days:num('LARGE_DAYS',30),showtimesPerDay:num('LARGE_SHOWTIMES_PER_DAY',20),rooms:num('LARGE_ROOMS',4),
        rows:num('LARGE_ROWS',15),seatsPerRow:num('LARGE_SEATS_PER_ROW',20),vipRows:num('LARGE_VIP_ROWS',2),
        movies:num('LARGE_MOVIES',50),fillRatio:num('LARGE_FILL_RATIO',0.78),cancelledShare:num('LARGE_CANCELLED_SHARE',0.3),
        heavyUsers:num('LARGE_HEAVY_USERS',20),heavyMin:num('LARGE_HEAVY_MIN_BOOKINGS',200),heavyMax:num('LARGE_HEAVY_MAX_BOOKINGS',500),
        normalUsers:num('LARGE_NORMAL_USERS',300),concurrency:num('SEED_CONCURRENCY',8),randomSeed:num('SEED_RANDOM',42)};
    const slotMinutes=140; // 120-minute movie + 15-minute cleaning + 5-minute gap
    const slotsPerRoom=Math.ceil(cfg.showtimesPerDay/cfg.rooms);
    if(cfg.rows<1 || cfg.rows>26 || cfg.seatsPerRow<1 || cfg.vipRows<0 || cfg.vipRows>cfg.rows) throw new Error('Invalid LARGE_ROWS/SEATS_PER_ROW/VIP_ROWS');
    if(slotsPerRoom*slotMinutes>18*60) throw new Error('Too many showtimes per room per day; raise LARGE_ROOMS');
    if(cfg.fillRatio<=0 || cfg.fillRatio>1 || cfg.cancelledShare<0 || cfg.cancelledShare>=0.5) throw new Error('Use 0<LARGE_FILL_RATIO<=1 and 0<=LARGE_CANCELLED_SHARE<0.5');
    if(cfg.heavyMin<1 || cfg.heavyMax<cfg.heavyMin || cfg.normalUsers<1 || cfg.concurrency<1) throw new Error('Invalid user or concurrency settings');
    const random=mulberry32(cfg.randomSeed);
    const randomInt=(min,max)=>min+Math.floor(random()*(max-min+1));
    const shuffle=values=>{for(let i=values.length-1;i>0;i--){const j=Math.floor(random()*(i+1));[values[i],values[j]]=[values[j],values[i]];}return values;};
    const started=Date.now();
    const log=message=>console.log('[large '+((Date.now()-started)/1000).toFixed(0)+'s] '+message);

    // 1. Rooms and showtimes. Showtimes start two days ahead so the 30-minute cancel rule never blocks seeding.
    const rowLabels='ABCDEFGHIJKLMNOPQRSTUVWXYZ'.slice(0,cfg.rows).split('');
    const rooms=[];
    for(let index=0;index<cfg.rooms;index++) {
        const room=await api('POST','/api/v1/admin/auditoriums',{name:prefix+'-large-'+index,seatLayout:rowLabels.map((row,rowIndex)=>
            ({row,from:1,to:cfg.seatsPerRow,type:rowIndex>=cfg.rows-cfg.vipRows?'VIP':'STANDARD'}))},admin,201);
        rooms.push({id:room.id,seats:room.seats.map(seat=>seat.id)});
    }
    const showtimeMovies=saved.movieIds.slice(0,Math.max(1,cfg.movies));
    const firstDay=new Date(Date.now()+48*3600000);firstDay.setUTCHours(0,0,0,0);
    const plans=[];
    for(let day=0;day<cfg.days;day++) for(let slot=0;slot<slotsPerRoom;slot++) for(let roomIndex=0;roomIndex<cfg.rooms;roomIndex++) {
        if(slot*cfg.rooms+roomIndex>=cfg.showtimesPerDay) continue;
        const startsAt=new Date(firstDay.getTime()+day*86400000+(6*60+slot*slotMinutes)*60000);
        plans.push({roomIndex,startsAt,movieId:showtimeMovies[plans.length%showtimeMovies.length]});
    }
    saved.showtimes=[];
    await pool(plans,cfg.concurrency,async plan=>{
        const showtime=await api('POST','/api/v1/admin/showtimes',{movieId:plan.movieId,auditoriumId:rooms[plan.roomIndex].id,
            startsAt:plan.startsAt.toISOString(),basePrice:90000},admin,201);
        plan.id=showtime.id;
    });
    saved.showtimes=plans.map(plan=>({id:plan.id,movieId:plan.movieId,auditoriumId:rooms[plan.roomIndex].id,
        startsAt:plan.startsAt.toISOString(),date:plan.startsAt.toISOString().slice(0,10)}));
    persist();
    log('created '+rooms.length+' rooms and '+plans.length+' showtimes');

    // 2. Booking plan per showtime: fill seats, cancel a share of bookings, then rebook the freed seats.
    // Cancelling q of the first round and rebooking gives q/(1+q) cancelled rows, so q = share/(1-share).
    const cancelProbability=cfg.cancelledShare/(1-cfg.cancelledShare);
    const chunk=seats=>{const result=[];for(let i=0;i<seats.length;){const size=Math.min(randomInt(1,8),seats.length-i);result.push(seats.slice(i,i+size));i+=size;}return result;};
    const allBookings=[];
    for(const plan of plans) {
        const seats=shuffle([...rooms[plan.roomIndex].seats]).slice(0,Math.floor(cfg.fillRatio*rooms[plan.roomIndex].seats.length));
        plan.first=chunk(seats).map(seatIds=>({seatIds,cancel:random()<cancelProbability}));
        plan.rebook=chunk(plan.first.filter(booking=>booking.cancel).flatMap(booking=>booking.seatIds)).map(seatIds=>({seatIds,cancel:false}));
        allBookings.push(...plan.first,...plan.rebook);
    }

    // 3. Users: heavy users get 200-500 bookings each (including later-cancelled ones); the rest are spread over normal users.
    const heavyQuotas=Array.from({length:cfg.heavyUsers},()=>randomInt(cfg.heavyMin,cfg.heavyMax));
    const heavyTotal=heavyQuotas.reduce((sum,value)=>sum+value,0);
    if(heavyTotal>0.8*allBookings.length) throw new Error('Heavy user quotas ('+heavyTotal+') exceed 80% of planned bookings ('+allBookings.length+'); lower LARGE_HEAVY_* or raise the dataset size');
    const users=await pool(Array.from({length:cfg.heavyUsers+cfg.normalUsers},(_,index)=>index),cfg.concurrency,async index=>{
        const username=prefix+'-'+(index<cfg.heavyUsers?'heavy-':'user-')+index;
        const user=await api('POST','/api/v1/auth/register',{username,password},undefined,201);
        return {userId:user.id,username,token:await login(username),bookingCount:0};
    });
    const owners=[];
    heavyQuotas.forEach((quota,index)=>{for(let i=0;i<quota;i++) owners.push(index);});
    while(owners.length<allBookings.length) owners.push(cfg.heavyUsers+Math.floor(random()*cfg.normalUsers));
    shuffle(owners).forEach((owner,index)=>{allBookings[index].user=users[owner];users[owner].bookingCount++;});
    log('registered '+users.length+' users; planned '+allBookings.length+' bookings');

    // 4. Execute. Showtimes run in parallel; inside one showtime the steps stay ordered (book, cancel, rebook).
    const asUser=async(user,method,route,body,expected,headers)=>{
        try { return await api(method,route,body,user.token,expected,headers); }
        catch(error) { if(error.status!==401) throw error; user.token=await login(user.username); return api(method,route,body,user.token,expected,headers); }
    };
    const book=async(plan,booking)=>{
        const created=await asUser(booking.user,'POST','/api/v1/bookings',{showtimeId:plan.id,seatIds:booking.seatIds},201,
            {'Idempotency-Key':crypto.randomUUID()});
        booking.id=created.bookingId;
    };
    let done=0;
    await pool(plans,cfg.concurrency,async plan=>{
        for(const booking of plan.first) await book(plan,booking);
        for(const booking of plan.first.filter(value=>value.cancel)) await asUser(booking.user,'DELETE','/api/v1/bookings/'+booking.id,undefined,204);
        for(const booking of plan.rebook) await book(plan,booking);
        if(++done%50===0 || done===plans.length) log('bookings done for '+done+'/'+plans.length+' showtimes');
    });

    const seatRows=allBookings.reduce((sum,booking)=>sum+booking.seatIds.length,0);
    const cancelledRows=allBookings.filter(booking=>booking.cancel).reduce((sum,booking)=>sum+booking.seatIds.length,0);
    const heavy=users.slice(0,cfg.heavyUsers);
    saved.heavyUsers=heavy.map(({userId,username,token,bookingCount})=>({userId,username,token,bookingCount}));
    saved.largeStats={config:cfg,rooms:rooms.length,seatsPerRoom:cfg.rows*cfg.seatsPerRow,showtimes:plans.length,
        users:users.length,bookings:allBookings.length,cancelledBookings:allBookings.filter(booking=>booking.cancel).length,
        bookingSeatRows:seatRows,cancelledBookingSeatRows:cancelledRows,cancelledShareActual:seatRows?cancelledRows/seatRows:0,
        heavyBookingsMin:Math.min(...heavy.map(user=>user.bookingCount)),heavyBookingsMax:Math.max(...heavy.map(user=>user.bookingCount)),
        normalBookingsAverage:(allBookings.length-heavyTotal)/cfg.normalUsers,seedSeconds:(Date.now()-started)/1000};
    persist();
    log('done: '+seatRows+' booking_seats rows ('+(100*saved.largeStats.cancelledShareActual).toFixed(1)+'% cancelled)');
}
async function pool(items,limit,worker) {
    const results=new Array(items.length);let next=0;
    const run=async()=>{while(next<items.length){const index=next++;results[index]=await worker(items[index],index);}};
    await Promise.all(Array.from({length:Math.min(limit,items.length)},run));
    return results;
}
function mulberry32(seed) {
    let state=seed>>>0;
    return ()=>{state=(state+0x6D2B79F5)>>>0;let t=state;t=Math.imul(t^(t>>>15),t|1);t^=t+Math.imul(t^(t>>>7),t|61);return ((t^(t>>>14))>>>0)/4294967296;};
}
