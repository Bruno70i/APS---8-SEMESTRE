import { createHash, randomUUID } from 'node:crypto';
import { z } from 'zod';
import { toReport,toSnapshot } from './db.js';
import { crossings,crossingIds,crossingById } from './crossings.js';

export const categories=['fila','embarque_lento','interrompida','clima','acidente'];
export const STALE_AFTER_MS=10*60_000;
export const reportInput=z.object({
  crossingId:z.enum(crossingIds), side:z.enum(['A','B']), category:z.enum(categories),
  waitMinutes:z.number().int().min(0).max(600).nullable().default(null),
  description:z.string().trim().max(400).default(''),
  latitude:z.number().min(-90).max(90), longitude:z.number().min(-180).max(180),
}).strict();
export const hash=value=>createHash('sha256').update(value).digest('hex');
export class HttpError extends Error {constructor(statusCode,message){super(message);this.statusCode=statusCode;}}

async function transaction(pool,operation){
  const client=await pool.connect();
  try{await client.query('BEGIN');const result=await operation(client);await client.query('COMMIT');return result;}
  catch(error){await client.query('ROLLBACK');throw error;}
  finally{client.release();}
}
// "origin" diz quem gerou o evento (api-1, api-2 ou collector), para a tela Bastidores desenhar o caminho.
async function appendEvent(client,data,origin){
  const event={eventId:randomUUID(),...data,origin};
  await client.query('INSERT INTO outbox_events(id,payload) VALUES($1,$2)',[event.eventId,event]);
}

export async function saveReport(pool,authorId,input,key,origin='api'){
  const requestHash=hash(JSON.stringify(input));
  return transaction(pool,async client=>{
    const inserted=await client.query(`INSERT INTO reports(id,author_id,crossing_id,side,category,wait_minutes,description,latitude,longitude,idempotency_key,request_hash)
      VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11) ON CONFLICT(author_id,idempotency_key) DO NOTHING RETURNING *`,
    [randomUUID(),authorId,input.crossingId,input.side,input.category,input.waitMinutes,input.description,input.latitude,input.longitude,key,requestHash]);
    if(inserted.rowCount){
      const report=toReport(inserted.rows[0]);
      await appendEvent(client,{type:'report.created',reportId:report.id,crossingId:report.crossingId,version:report.version},origin);
      return {report,replayed:false};
    }
    const existing=(await client.query('SELECT * FROM reports WHERE author_id=$1 AND idempotency_key=$2',[authorId,key])).rows[0];
    if(existing.request_hash!==requestHash)throw new HttpError(409,'Esta chave já foi usada para outro relato.');
    // Reenvio do mesmo relato (idempotência): nada é duplicado, só contamos para mostrar na tela Bastidores.
    await client.query(`INSERT INTO system_counters(name,value) VALUES('idempotent_replays',1) ON CONFLICT(name) DO UPDATE SET value=system_counters.value+1`);
    return {report:toReport(existing),replayed:true};
  });
}
export async function resolveReport(pool,authorId,id,origin='api'){
  return transaction(pool,async client=>{
    const result=await client.query('SELECT * FROM reports WHERE id=$1 FOR UPDATE',[id]);
    if(!result.rowCount)throw new HttpError(404,'Relato não encontrado.');
    const row=result.rows[0];
    if(row.author_id!==authorId)throw new HttpError(403,'Somente quem publicou pode encerrar este relato.');
    if(row.status==='resolved')return toReport(row);
    const updated=await client.query("UPDATE reports SET status='resolved',version=version+1,updated_at=now() WHERE id=$1 RETURNING *",[id]);
    const report=toReport(updated.rows[0]);
    await appendEvent(client,{type:'report.resolved',reportId:report.id,crossingId:report.crossingId,version:report.version},origin);
    return report;
  });
}

const changed=(previous,next)=>!previous||['wait_a','wait_b','boats','operating','weather_a','weather_b','notice'].some(field=>previous[field]!==next[field]);
// Grava uma leitura de cada travessia. O evento só sai quando algo visível mudou, para não acordar os celulares à toa.
export async function recordSnapshots(pool,statuses,feedUrl=null){
  return transaction(pool,async client=>{
    let changes=0;
    for(const s of statuses){
      const row={wait_a:s.waitA,wait_b:s.waitB,boats:s.boats,operating:s.operating,weather_a:s.weatherA,weather_b:s.weatherB,notice:s.notice};
      const previous=(await client.query('SELECT * FROM crossing_snapshots WHERE crossing_id=$1 ORDER BY collected_at DESC LIMIT 1',[s.crossingId])).rows[0];
      await client.query(`INSERT INTO crossing_snapshots(crossing_id,wait_a,wait_b,boats,operating,weather_a,weather_b,period,notice,source,source_time)
        VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,'oficial',$10)`,[s.crossingId,s.waitA,s.waitB,s.boats,s.operating,s.weatherA,s.weatherB,s.period,s.notice,s.sourceTime]);
      if(changed(previous,row)){changes++;await appendEvent(client,{type:'crossing.updated',crossingId:s.crossingId},'collector');}
    }
    const state=(await client.query('SELECT failures,last_success_at FROM collector_state WHERE id=1 FOR UPDATE')).rows[0];
    await client.query(`INSERT INTO collector_state(id,mode,feed_url,last_attempt_at,last_success_at,last_error,failures) VALUES(1,'oficial',$1,now(),now(),NULL,0)
      ON CONFLICT(id) DO UPDATE SET feed_url=COALESCE($1,collector_state.feed_url),last_attempt_at=now(),last_success_at=now(),last_error=NULL,failures=0`,[feedUrl]);
    if(state?.failures>0)await appendEvent(client,{type:'source.status',available:true},'collector');
    return {saved:statuses.length,changes};
  });
}
export async function recordFailure(pool,message){
  return transaction(pool,async client=>{
    const state=(await client.query(`INSERT INTO collector_state(id,mode,last_attempt_at,last_error,failures) VALUES(1,'oficial',now(),$1,1)
      ON CONFLICT(id) DO UPDATE SET last_attempt_at=now(),last_error=$1,failures=collector_state.failures+1 RETURNING failures`,[String(message).slice(0,300)])).rows[0];
    if(state.failures===1)await appendEvent(client,{type:'source.status',available:false},'collector');
    return state.failures;
  });
}

export async function sourceState(pool){
  const row=(await pool.query('SELECT * FROM collector_state WHERE id=1')).rows[0];
  if(!row)return {mode:null,available:false,lastSuccessAt:null,lastError:'Coletor ainda não executou.'};
  const fresh=row.last_success_at&&Date.now()-row.last_success_at.getTime()<STALE_AFTER_MS;
  return {mode:row.mode,available:Boolean(fresh)&&row.failures===0,lastSuccessAt:row.last_success_at?.toISOString()??null,lastError:row.last_error};
}
export async function listCrossings(pool){
  const latest=(await pool.query('SELECT DISTINCT ON (crossing_id) * FROM crossing_snapshots ORDER BY crossing_id,collected_at DESC')).rows;
  // Estimativa da comunidade: mediana das esperas informadas nos últimos 45 minutos, por lado.
  const community=(await pool.query(`SELECT crossing_id,side,percentile_cont(0.5) WITHIN GROUP (ORDER BY wait_minutes) AS median,count(*)::int AS reports
    FROM reports WHERE status='active' AND wait_minutes IS NOT NULL AND created_at>now()-interval '45 minutes' GROUP BY crossing_id,side`)).rows;
  // Relatos de "travessia parada" dos últimos 45 minutos sinalizam paralisação antes de o painel oficial mudar.
  const active=(await pool.query(`SELECT crossing_id,count(*)::int AS total,
      count(*) FILTER (WHERE category='interrompida' AND created_at>now()-interval '45 minutes')::int AS stops
    FROM reports WHERE status='active' AND created_at>now()-interval '3 hours' GROUP BY crossing_id`)).rows;
  return crossings.map(c=>{
    const row=latest.find(r=>r.crossing_id===c.id);
    const reports=active.find(r=>r.crossing_id===c.id);
    const side=s=>{const m=community.find(r=>r.crossing_id===c.id&&r.side===s);return m?{waitMinutes:Math.round(m.median),reports:m.reports}:null;};
    return {...c,status:row?toSnapshot(row,Date.now()-row.collected_at.getTime()>STALE_AFTER_MS):null,
      community:{A:side('A'),B:side('B')},activeReports:reports?.total??0,stopReports:reports?.stops??0};
  });
}
export async function crossingHistory(pool,id,days){
  if(!crossingById(id))throw new HttpError(404,'Travessia não encontrada.');
  const hourly=(await pool.query(`SELECT extract(hour FROM collected_at AT TIME ZONE 'America/Sao_Paulo')::int AS hour,
      round(avg(wait_a))::int AS wait_a,round(avg(wait_b))::int AS wait_b,count(*)::int AS samples
    FROM crossing_snapshots WHERE crossing_id=$1 AND operating AND collected_at>now()-make_interval(days=>$2) GROUP BY 1 ORDER BY 1`,[id,days])).rows;
  const recent=(await pool.query(`SELECT to_timestamp(floor(extract(epoch FROM collected_at)/900)*900) AS bucket,round(avg(wait_a))::int AS wait_a,round(avg(wait_b))::int AS wait_b
    FROM crossing_snapshots WHERE crossing_id=$1 AND operating AND collected_at>now()-interval '6 hours' GROUP BY 1 ORDER BY 1`,[id])).rows;
  // Leituras com a travessia parada ficam fora das médias: espera zero com balsa parada não é horário bom.
  // Melhor horário só entre 6h e 22h: recomendar a madrugada não ajuda quem precisa atravessar.
  const useful=hourly.filter(h=>h.hour>=6&&h.hour<=22);
  const best=field=>useful.length?useful.reduce((a,b)=>b[field]<a[field]?b:a).hour:null;
  return {crossingId:id,days,
    hourly:hourly.map(h=>({hour:h.hour,waitA:h.wait_a,waitB:h.wait_b,samples:h.samples})),
    recent:recent.map(r=>({at:r.bucket.toISOString(),waitA:r.wait_a,waitB:r.wait_b})),
    bestHour:{A:best('wait_a'),B:best('wait_b')}};
}
