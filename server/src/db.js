// Banco de dados: conexão com o PostgreSQL, criação das tabelas e conversão das linhas para o formato da API.
import pg from 'pg';
import { readFile } from 'node:fs/promises';

// Endereço do banco. No Docker vem da variável DATABASE_URL; o valor padrão serve para rodar fora do Docker.
export const enderecoBanco=()=>process.env.DATABASE_URL || 'postgresql://viaagora:viaagora_local_only@127.0.0.1:15439/viaagora';

// Conjunto de conexões reaproveitadas pelo processo (no máximo 10 abertas ao mesmo tempo).
export function criarPool(){return new pg.Pool({connectionString:enderecoBanco(),connectionTimeoutMillis:3000,max:10});}

// Cria as tabelas (migração). A trava impede que duas réplicas que sobem juntas rodem a migração ao mesmo tempo.
export async function migrar(pool){
  const conexao=await pool.connect();
  try{
    await conexao.query('BEGIN');
    await conexao.query('SELECT pg_advisory_xact_lock(43104310)');
    await conexao.query(await readFile(new URL('../migrations/001.sql',import.meta.url),'utf8'));
    await conexao.query('COMMIT');
  }catch(erro){await conexao.query('ROLLBACK');throw erro;}finally{conexao.release();}
}

// Linha da tabela "reports" -> relato no formato JSON da API.
// Os nomes dos campos (authorId, crossingId...) são o contrato com o aplicativo e não podem mudar.
export function paraRelato(linha){return {id:linha.id,authorId:linha.author_id,crossingId:linha.crossing_id,side:linha.side,category:linha.category,
  waitMinutes:linha.wait_minutes,description:linha.description,latitude:linha.latitude,longitude:linha.longitude,status:linha.status,version:linha.version,
  createdAt:linha.created_at.toISOString(),updatedAt:linha.updated_at.toISOString()};}

// Linha da tabela "crossing_snapshots" (leitura do painel oficial) -> situação da travessia na API.
export function paraLeitura(linha,atrasada=false){return {waitA:linha.wait_a,waitB:linha.wait_b,boats:linha.boats,operating:linha.operating,
  weatherA:linha.weather_a,weatherB:linha.weather_b,period:linha.period,notice:linha.notice,source:linha.source,
  sourceTime:linha.source_time?.toISOString()??null,collectedAt:linha.collected_at.toISOString(),stale:atrasada};}
