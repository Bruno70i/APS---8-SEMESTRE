// Sinal de vida (heartbeat) dos processos, mostrado na aba Bastidores do aplicativo.
import os from 'node:os';

// Nome do processo (ex.: api-1, api-2, worker, collector), definido no Docker Compose.
export const nomeDoProcesso=papel=>process.env.NODE_NAME||`${papel}-${os.hostname().slice(0,6)}`;

// A cada 5 s o processo grava "estou vivo" no banco. Quem parar de gravar aparece como fora do ar.
// "info" devolve dados extras do processo (por exemplo, quantos celulares estão ligados na réplica).
export function iniciarSinalDeVida(pool,{processo,papel,info=()=>({}),intervaloMs=5000}){
  let primeiro=true;
  const pulsar=async()=>{
    try{
      await pool.query(`INSERT INTO node_heartbeats(node,role,info,started_at,last_seen) VALUES($1,$2,$3,now(),now())
        ON CONFLICT(node) DO UPDATE SET role=$2,info=$3,last_seen=now()${primeiro?',started_at=now()':''}`,[processo,papel,info()]);
      primeiro=false;
    }catch{}
  };
  void pulsar();
  const relogio=setInterval(pulsar,intervaloMs);relogio.unref();
  return {pulsar,parar:()=>clearInterval(relogio)};
}
