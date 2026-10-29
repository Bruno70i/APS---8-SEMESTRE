import {buildApp} from './app.js';
import {createPool,migrate} from './db.js';
import {rabbitUrl} from './broker.js';
import {nodeName,startHeartbeat} from './heartbeat.js';
const pool=createPool();
await migrate(pool);
const replica=nodeName('api');
const trust=process.env.TRUST_PROXY;
const app=await buildApp({pool,brokerUrl:rabbitUrl(),replica,trustProxy:trust?trust.split(',').map(v=>v.trim()):false});
await app.listen({host:'0.0.0.0',port:Number(process.env.PORT||4310)});
const heartbeat=startHeartbeat(pool,{node:replica,role:'api',info:()=>({clients:app.realtime.clients,broker:app.realtime.connected})});
console.log(`ViaAgora: API ${replica} iniciada na porta`,app.server.address().port);
let closing=false;
async function shutdown(){if(closing)return;closing=true;heartbeat.stop();await app.close();await pool.end();}
process.on('SIGINT',shutdown);process.on('SIGTERM',shutdown);
