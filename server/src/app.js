import Fastify from 'fastify';
import rateLimit from '@fastify/rate-limit';
import { randomBytes,randomUUID } from 'node:crypto';
import { z } from 'zod';
import { hash,HttpError,reportInput,categories,saveReport,resolveReport,listCrossings,crossingHistory,sourceState } from './domain.js';
import { crossingIds } from './crossings.js';
import { toReport } from './db.js';
import {attachRealtime} from './broker.js';
import {systemState} from './system.js';

const uuid=z.string().uuid();
const listQuery=z.object({crossing:z.enum(crossingIds).optional(),category:z.enum(categories).optional(),status:z.enum(['active','resolved']).optional(),
  sinceMinutes:z.coerce.number().int().min(1).max(10080).default(180),limit:z.coerce.number().int().min(1).max(500).default(200)}).strict();
const historyQuery=z.object({days:z.coerce.number().int().min(1).max(30).default(7)}).strict();
// trustProxy: atrás do Nginx, o limite de requisições deve contar o IP real de cada usuário, não o do proxy.
export async function buildApp({pool,brokerStatus=()=>false,brokerUrl=null,replica='api',trustProxy=false}){
  const app=Fastify({bodyLimit:8192,logger:false,trustProxy});
  const realtime=brokerUrl?attachRealtime(app,brokerUrl,{replica,pool}):null;
  app.decorate('realtime',realtime);
  await app.register(rateLimit,{max:300,timeWindow:'1 minute'});
  app.setErrorHandler((error,request,reply)=>{
    const status=error.statusCode || (error instanceof z.ZodError?400:503);
    if(status>=500)console.error('Request failed:',error.message);
    reply.code(status).send({message:status>=500?'Serviço temporariamente indisponível. Tente novamente.':error instanceof z.ZodError?'Confira travessia, lado, tipo, espera e coordenadas.':error.message});
  });
  async function authenticate(request){
    const token=request.headers.authorization?.match(/^Bearer ([A-Za-z0-9_-]{43})$/)?.[1];
    if(!token)throw new HttpError(401,'Identifique este aparelho antes de publicar.');
    const result=await pool.query('SELECT id FROM participants WHERE token_hash=$1',[hash(token)]);
    if(!result.rowCount)throw new HttpError(401,'Participação inválida. Reconecte o aplicativo.');
    return result.rows[0].id;
  }
  app.post('/v1/participants',{config:{rateLimit:{max:15,timeWindow:'1 minute'}}},async(request,reply)=>{
    const token=randomBytes(32).toString('base64url'),participantId=randomUUID();
    await pool.query('INSERT INTO participants(id,token_hash) VALUES($1,$2)',[participantId,hash(token)]);
    return reply.code(201).send({participantId,token});
  });
  app.get('/v1/crossings',async()=>({crossings:await listCrossings(pool),source:await sourceState(pool),serverTime:new Date().toISOString()}));
  app.get('/v1/crossings/:id/history',async request=>{
    const {days}=historyQuery.parse(request.query);
    return crossingHistory(pool,z.string().max(60).parse(request.params.id),days);
  });
  app.get('/v1/reports',async request=>{
    const filters=listQuery.parse(request.query),values=[filters.sinceMinutes],clauses=['created_at>now()-make_interval(mins=>$1)'];
    for(const [field,column] of [['crossing','crossing_id'],['category','category'],['status','status']])if(filters[field]){values.push(filters[field]);clauses.push(`${column}=$${values.length}`);}
    values.push(filters.limit);
    const rows=await pool.query(`SELECT * FROM reports WHERE ${clauses.join(' AND ')} ORDER BY created_at DESC LIMIT $${values.length}`,values);
    return {reports:rows.rows.map(toReport),serverTime:new Date().toISOString()};
  });
  app.post('/v1/reports',async(request,reply)=>{
    const authorId=await authenticate(request),input=reportInput.parse(request.body);
    const key=uuid.safeParse(request.headers['idempotency-key']);
    if(!key.success)throw new HttpError(400,'Informe uma chave de envio válida.');
    const result=await saveReport(pool,authorId,input,key.data,replica);
    return reply.code(result.replayed?200:201).send({report:result.report});
  });
  app.patch('/v1/reports/:id/resolve',async request=>{
    const authorId=await authenticate(request),id=uuid.parse(request.params.id);
    return {report:await resolveReport(pool,authorId,id,replica)};
  });
  app.get('/v1/system',async()=>systemState(pool,{replica,brokerConnected:realtime?realtime.connected:brokerStatus()}));
  app.get('/health',async(request,reply)=>{
    let database='up',source=null;
    try{await pool.query('SELECT 1');source=await sourceState(pool);}catch{database='down';}
    const broker=(realtime?realtime.connected:brokerStatus())?'up':'down',ok=database==='up'&&broker==='up';
    return reply.code(ok?200:503).send({status:ok?'ok':'degraded',services:{database,broker,collector:source?.available?'up':'down'},name:'ViaAgora'});
  });
  return app;
}
