// Comando avulso para criar ou atualizar as tabelas do banco: node src/migrate.js
import {criarPool,migrar} from './db.js';
const pool=criarPool();
try{await migrar(pool);console.log('ViaAgora: banco atualizado.');}finally{await pool.end();}
