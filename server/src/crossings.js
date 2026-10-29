// Travessias acompanhadas e leitura do painel oficial da SEMIL-SP (web scraping).
import { z } from 'zod';

// Travessias da Baixada Santista acompanhadas pelo ViaAgora. Terminais e rotas tirados das linhas de balsa do
// OpenStreetMap (vias 48452393, 496378138 e 466892175); a rota vai do ponto A ao ponto B.
// Os nomes dos campos (name, pointA, route...) são enviados ao aplicativo e fazem parte da API.
export const travessias=[
  {id:'santos-guaruja',name:'Santos ⇄ Guarujá',kind:'veiculos',
    pointA:{name:'Santos',address:'Praça Gago Coutinho, 525 - Ponta da Praia',latitude:-23.98692,longitude:-46.29379},
    pointB:{name:'Guarujá',address:'Av. Ademar de Barros, 3300',latitude:-23.98832,longitude:-46.28944},
    route:[[-23.98692,-46.29379],[-23.98832,-46.28944]]},
  {id:'santos-vicente-de-carvalho',name:'Santos ⇄ Vicente de Carvalho',kind:'pedestres',
    pointA:{name:'Santos',address:'Praça da República - Centro',latitude:-23.93171,longitude:-46.32451},
    pointB:{name:'Vicente de Carvalho',address:'Av. Thiago Ferreira',latitude:-23.93964,longitude:-46.30827},
    route:[[-23.93171,-46.32451],[-23.93075,-46.32423],[-23.93068,-46.32406],[-23.93152,-46.31825],[-23.93313,-46.31383],
      [-23.93512,-46.31194],[-23.94001,-46.31023],[-23.93963,-46.30835],[-23.93964,-46.30827]]},
  {id:'bertioga-guaruja',name:'Bertioga ⇄ Guarujá',kind:'veiculos',
    pointA:{name:'Bertioga',address:'Av. Henrique Costabile, 50 - Jardim Veleiros',latitude:-23.85639,longitude:-46.14465},
    pointB:{name:'Guarujá',address:'Estrada Guarujá-Bertioga, km 29',latitude:-23.86028,longitude:-46.14337},
    route:[[-23.85639,-46.14465],[-23.86028,-46.14337]]},
];
export const idsTravessias=travessias.map(t=>t.id);
export const travessiaPorId=id=>travessias.find(t=>t.id===id);

export const ENDERECO_SITE='https://semil.sp.gov.br/travessias/';
// Endereço conhecido do serviço do painel, usado se a varredura do site falhar.
export const ENDERECO_SERVICO_PADRAO='https://drivetis-travessias.brazilsouth.cloudapp.azure.com:1444/TravessiaService/PT';
const cabecalhos={'User-Agent':'Mozilla/5.0 (compatible; ViaAgora-APS/1.0; projeto academico)','Accept':'text/html,application/xhtml+xml,application/json','Accept-Language':'pt-BR,pt;q=0.9'};

// "Santos Guarujá" -> "santos-guaruja": sem acentos, minúsculo e com hífens.
export const gerarSlug=valor=>valor.normalize('NFD').replace(/[̀-ͯ]/g,'').toLowerCase().trim().replace(/[^a-z0-9]+/g,'-').replace(/^-|-$/g,'');
// Aviso fixo do 0800 que aparece em todas as travessias; não informa nada sobre a situação atual.
const avisoGenerico=/^(o\s+)?0800[-\d\s]*.{0,40}24 horas/i;
const removerHtml=valor=>(valor??'').replace(/<[^>]*>/g,' ').replace(/&nbsp;/g,' ').replace(/\s+/g,' ').trim();

// Página da SEMIL -> <script ...travessia-service...> -> variável getInfo (ignora linhas comentadas).
export function encontrarScriptDoPainel(html,base=ENDERECO_SITE){
  const endereco=html.match(/<script[^>]+src="([^"]*travessia-service[^"]*)"/i)?.[1];
  return endereco?new URL(endereco,base).toString():null;
}
export function encontrarEnderecoServico(script){
  for(const linha of script.split(/\r?\n/)){
    if(linha.trim().startsWith('//'))continue;
    const endereco=linha.match(/getInfo\s*=\s*["'](https?:\/\/[^"']+)["']/)?.[1];
    if(endereco)return endereco.endsWith('/')?endereco:endereco+'/';
  }
  return null;
}

// Baixa uma página ou um JSON, desistindo depois de 15 segundos.
async function baixar(buscarImpl,endereco,tipo){
  const resposta=await buscarImpl(endereco,{headers:cabecalhos,signal:AbortSignal.timeout(15000)});
  if(!resposta.ok)throw new Error(`Fonte respondeu ${resposta.status} em ${new URL(endereco).host}`);
  return tipo==='json'?resposta.json():resposta.text();
}
// Refaz o caminho do navegador: página -> script do painel -> endereço do serviço de dados.
export async function descobrirEnderecoServico(buscarImpl=fetch){
  const html=await baixar(buscarImpl,ENDERECO_SITE,'text');
  const script=encontrarScriptDoPainel(html);
  if(!script)throw new Error('Script do painel não encontrado na página.');
  const base=encontrarEnderecoServico(await baixar(buscarImpl,script,'text'));
  if(!base)throw new Error('Endereço do serviço não encontrado no script.');
  return base+'PT';
}

// Formato de cada item do JSON do painel oficial (validado com Zod; itens fora do formato são ignorados).
const itemPainel=z.object({
  TravessiaPontoA:z.string(),TravessiaPontoB:z.string(),
  EsperaPontoA:z.number().int().min(0).max(1440),EsperaPontoB:z.number().int().min(0).max(1440),
  NumEmbarca:z.number().int().min(0).max(50),EmbarcaAtivo:z.boolean(),
  ClimaA:z.string().nullish(),ClimaB:z.string().nullish(),DiaNoite:z.string().nullish(),
  AvisoUsuarioApp:z.string().nullish(),AvisoUsuario:z.string().nullish(),DataConsulta:z.string().nullish(),
}).passthrough();

// "10/28/2026 9:16:41 PM" no horário de Brasília (UTC-3, sem horário de verão) -> data ISO em UTC.
export function lerHorarioFonte(valor){
  const m=valor?.match(/^(\d{1,2})\/(\d{1,2})\/(\d{4}) (\d{1,2}):(\d{2}):(\d{2}) (AM|PM)$/);
  if(!m)return null;
  let hora=Number(m[4])%12;if(m[7]==='PM')hora+=12;
  const data=new Date(Date.UTC(Number(m[3]),Number(m[1])-1,Number(m[2]),hora+3,Number(m[5]),Number(m[6])));
  return Number.isNaN(data.getTime())?null:data.toISOString();
}

const limparAviso=valor=>{const texto=removerHtml(valor).slice(0,400);return texto&&!avisoGenerico.test(texto)?texto:null;};
// O painel nem sempre zera as embarcações numa paralisação; às vezes só avisa no texto. Restrições parciais
// (ex.: só veículos pesados) e avisos de retomada não contam como travessia parada.
const palavrasDeParada=/\b(interrompid[ao]s?|paralisad[ao]s?|paralisacao|suspens[ao]s?|interditad[ao]s?|fechad[ao]s?|sem operacao)\b/;
const retomada=/(retomad|normaliz|restabelecid|liberad|reabert|voltou a operar)/;
const parcial=/(pesad|caminh|carga)/;
// Frases que falam da travessia inteira valem mesmo se o aviso também citar veículos pesados.
const paradaTotal=/(travessia (esta |foi )?(interrompid|paralisad|suspens|fechad)|operacao (esta |foi )?(suspens|interrompid|paralisad)|sem operacao|paralisacao (total|geral))/;
export function ehAvisoDeParada(texto){
  const simples=(texto??'').normalize('NFD').replace(/[̀-ͯ]/g,'').toLowerCase();
  if(retomada.test(simples))return false;
  return paradaTotal.test(simples)||(palavrasDeParada.test(simples)&&!parcial.test(simples));
}
// JSON do painel -> leituras das três travessias da Baixada Santista (as demais travessias do estado são ignoradas).
export function lerPainel(dados){
  if(!Array.isArray(dados))throw new Error(typeof dados==='string'?`Fonte recusou a consulta: ${dados}`:'Formato inesperado da fonte.');
  const leituras=[];
  for(const bruto of dados){
    const item=itemPainel.safeParse(bruto);
    if(!item.success)continue;
    const d=item.data,id=gerarSlug(`${d.TravessiaPontoA} ${d.TravessiaPontoB}`);
    if(!travessiaPorId(id))continue;
    const aviso=limparAviso(d.AvisoUsuarioApp||d.AvisoUsuario);
    leituras.push({crossingId:id,waitA:d.EsperaPontoA,waitB:d.EsperaPontoB,boats:d.NumEmbarca,operating:d.EmbarcaAtivo&&d.NumEmbarca>0&&!ehAvisoDeParada(aviso),
      weatherA:removerHtml(d.ClimaA)||null,weatherB:removerHtml(d.ClimaB)||null,period:removerHtml(d.DiaNoite)||null,
      notice:aviso,sourceTime:lerHorarioFonte(d.DataConsulta)});
  }
  return leituras;
}

export async function buscarPainelOficial(buscarImpl=fetch,enderecoServico=ENDERECO_SERVICO_PADRAO){
  return lerPainel(await baixar(buscarImpl,enderecoServico,'json'));
}
