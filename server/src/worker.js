import {createPool,migrate} from './db.js';
import {publishBatch} from './broker.js';
import {nodeName,startHeartbeat} from './heartbeat.js';
const pool=createPool();await migrate(pool);let stopping=false;
process.on('SIGINT',()=>{stopping=true;});process.on('SIGTERM',()=>{stopping=true;});
let published=0,lastPublishAt=null,loops=0;
const heartbeat=startHeartbeat(pool,{node:nodeName('worker'),role:'worker',info:()=>({published,lastPublishAt})});
console.log('ViaAgora: worker de eventos iniciado.');
while(!stopping){
  try{const count=await publishBatch(pool);if(count){published+=count;lastPublishAt=new Date().toISOString();console.log('Eventos publicados:',count);}}
  catch(error){console.error('Publicação adiada; dados preservados:',error.code||error.name);}
  // Limpeza simples, uma vez por hora: eventos já publicados há mais de 7 dias.
  if(++loops%4800===0)await pool.query("DELETE FROM outbox_events WHERE published_at<now()-interval '7 days'; DELETE FROM event_deliveries WHERE delivered_at<now()-interval '7 days'").catch(()=>{});
  await new Promise(resolve=>setTimeout(resolve,750));
}
heartbeat.stop();await pool.end();
