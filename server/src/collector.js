import {createPool,migrate} from './db.js';
import {createCollector} from './collect.js';
import {nodeName,startHeartbeat} from './heartbeat.js';
const pool=createPool();await migrate(pool);let stopping=false;
process.on('SIGINT',()=>{stopping=true;});process.on('SIGTERM',()=>{stopping=true;});
const interval=Math.max(30_000,Number(process.env.COLLECT_INTERVAL_MS||60_000));
const collector=createCollector({pool,feedUrl:process.env.TRAVESSIAS_FEED_URL||null});
console.log(`ViaAgora: coletor de travessias iniciado (a cada ${interval/1000}s).`);
let failures=0,runs=0,lastRunAt=null,lastOk=null;
const heartbeat=startHeartbeat(pool,{node:nodeName('collector'),role:'collector',info:()=>({lastRunAt,lastOk,failures})});
while(!stopping){
  try{const {saved,changes}=await collector.run();failures=0;lastOk=true;console.log(`Coleta: ${saved} travessias, ${changes} com mudança.`);}
  catch(error){failures++;lastOk=false;console.error('Coleta falhou; último dado preservado:',error.message);}
  lastRunAt=new Date().toISOString();
  if(++runs%60===0)await pool.query("DELETE FROM crossing_snapshots WHERE collected_at<now()-interval '30 days'").catch(()=>{});
  // Em falha, espera mais a cada tentativa (até 10 min) para não sobrecarregar a fonte oficial.
  const delay=Math.min(interval*2**Math.min(failures,4),600_000);
  for(let waited=0;waited<delay&&!stopping;waited+=500)await new Promise(resolve=>setTimeout(resolve,500));
}
heartbeat.stop();await pool.end();
