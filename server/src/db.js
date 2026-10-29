import pg from 'pg';
import { readFile } from 'node:fs/promises';
export const databaseUrl=()=>process.env.DATABASE_URL || 'postgresql://viaagora:viaagora_local_only@127.0.0.1:15439/viaagora';
export function createPool(){return new pg.Pool({connectionString:databaseUrl(),connectionTimeoutMillis:3000,max:10});}
export async function migrate(pool){
  const client=await pool.connect();
  try{
    await client.query('BEGIN');
    await client.query('SELECT pg_advisory_xact_lock(43104310)');
    await client.query(await readFile(new URL('../migrations/001.sql',import.meta.url),'utf8'));
    await client.query('COMMIT');
  }catch(error){await client.query('ROLLBACK');throw error;}finally{client.release();}
}
export function toReport(r){return {id:r.id,authorId:r.author_id,crossingId:r.crossing_id,side:r.side,category:r.category,waitMinutes:r.wait_minutes,description:r.description,latitude:r.latitude,longitude:r.longitude,status:r.status,version:r.version,createdAt:r.created_at.toISOString(),updatedAt:r.updated_at.toISOString()};}
export function toSnapshot(r,stale=false){return {waitA:r.wait_a,waitB:r.wait_b,boats:r.boats,operating:r.operating,weatherA:r.weather_a,weatherB:r.weather_b,period:r.period,notice:r.notice,source:r.source,sourceTime:r.source_time?.toISOString()??null,collectedAt:r.collected_at.toISOString(),stale};}
