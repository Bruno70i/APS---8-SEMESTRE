// Uma coleta da fonte oficial. Lê as páginas das travessias no site da Acquavias; se o site falhar, tenta o
// serviço JSON de reserva (antigo painel da SEMIL-SP). Os dados são normalizados e gravados. Falhas ficam
// registradas e o último dado bom continua disponível na API.
import { buscarAcquavias,buscarPainelOficial,ENDERECO_SITE,ENDERECO_SERVICO_PADRAO } from './crossings.js';
import { gravarLeituras,registrarFalha } from './domain.js';

export function criarColetor({pool,buscarImpl=fetch,enderecoServico=null,log=console}){
  const reserva=enderecoServico||ENDERECO_SERVICO_PADRAO;
  let endereco=null;
  return {
    // Endereço de onde veio a última leitura (site da Acquavias ou serviço de reserva).
    get enderecoServico(){return endereco;},
    async executar(){
      try{
        let leituras;
        try{leituras=await buscarAcquavias(buscarImpl);endereco=ENDERECO_SITE;}
        catch(erro){
          log.warn?.('Site da Acquavias falhou, tentando o serviço de reserva:',erro.message);
          try{leituras=await buscarPainelOficial(buscarImpl,reserva);endereco=reserva;}
          catch(erroReserva){throw new Error(`${erro.message} (reserva: ${erroReserva.message})`);}
        }
        if(!leituras.length)throw new Error('Nenhuma travessia da Baixada Santista na resposta da fonte.');
        return await gravarLeituras(pool,leituras,endereco);
      }catch(erro){await registrarFalha(pool,erro.message);throw erro;}
    },
  };
}
