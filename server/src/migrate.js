import {createPool,migrate} from './db.js';
const pool=createPool();
try{await migrate(pool);console.log('ViaAgora: banco atualizado.');}finally{await pool.end();}
