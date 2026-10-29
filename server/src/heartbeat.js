import os from 'node:os';

// Nome do processo na tela Bastidores (ex.: api-1, api-2, worker, collector), definido no Compose.
export const nodeName=role=>process.env.NODE_NAME||`${role}-${os.hostname().slice(0,6)}`;

// Sinal de vida: a cada 5 s o processo grava "estou vivo" no banco. Quem parar de gravar aparece como fora do ar.
export function startHeartbeat(pool,{node,role,info=()=>({}),intervalMs=5000}){
  let first=true;
  const beat=async()=>{
    try{
      await pool.query(`INSERT INTO node_heartbeats(node,role,info,started_at,last_seen) VALUES($1,$2,$3,now(),now())
        ON CONFLICT(node) DO UPDATE SET role=$2,info=$3,last_seen=now()${first?',started_at=now()':''}`,[node,role,info()]);
      first=false;
    }catch{}
  };
  void beat();
  const timer=setInterval(beat,intervalMs);timer.unref();
  return {beat,stop:()=>clearInterval(timer)};
}
