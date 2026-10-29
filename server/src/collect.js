// Uma coleta do painel oficial. Descobre o endereço do serviço varrendo o site (só quando ainda não sabe ou
// quando a leitura falha), lê os dados, normaliza e grava. Falhas ficam registradas e o último dado bom
// continua disponível na API.
import { descobrirEnderecoServico,buscarPainelOficial,ENDERECO_SERVICO_PADRAO } from './crossings.js';
import { gravarLeituras,registrarFalha } from './domain.js';

export function criarColetor({pool,buscarImpl=fetch,enderecoServico=null,log=console}){
  let endereco=enderecoServico;
  async function resolverEndereco(forcar){
    if(endereco&&!forcar)return endereco;
    try{endereco=await descobrirEnderecoServico(buscarImpl);log.info?.('Serviço das travessias encontrado no site:',endereco);}
    catch(erro){log.warn?.('Varredura do site falhou, usando endereço conhecido:',erro.message);endereco=endereco||ENDERECO_SERVICO_PADRAO;}
    return endereco;
  }
  return {
    get enderecoServico(){return endereco;},
    async executar(){
      try{
        let leituras;
        // Se a leitura falhar com o endereço guardado, varre o site de novo (o governo pode ter trocado o endereço).
        try{leituras=await buscarPainelOficial(buscarImpl,await resolverEndereco(false));}
        catch{leituras=await buscarPainelOficial(buscarImpl,await resolverEndereco(true));}
        if(!leituras.length)throw new Error('Nenhuma travessia da Baixada Santista na resposta da fonte.');
        return await gravarLeituras(pool,leituras,endereco);
      }catch(erro){await registrarFalha(pool,erro.message);throw erro;}
    },
  };
}
