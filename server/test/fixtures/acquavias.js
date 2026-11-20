// Monta uma página de travessia no mesmo formato do site da Acquavias (trechos copiados da marcação real):
// barra do topo com todas as travessias, espera e clima de cada lado e o aviso ao usuário.
const travessiasDoTopo=[
  {href:'/sao-sebastiao-ilhabela/',nomes:['São Sebastião','Ilhabela'],esperas:['30 min','30 min'],barcos:7,clima:'Parcialmente Nublado — Dia'},
  {href:'/santos-guaruja/',nomes:['Santos','Guarujá'],esperas:['30 min','40 min'],barcos:8,clima:'Encoberto — Dia'},
  {href:'/bertioga-guaruja/',nomes:['Bertioga','Guarujá'],esperas:['20 min','20 min'],barcos:2,clima:'Encoberto — Dia'},
  {href:'/santos-vicente-de-carvalho/',nomes:['Santos','Vicente de Carvalho'],esperas:['12 min','12 min'],barcos:3,clima:'Encoberto — Noite'},
];
const itemDoTopo=t=>`<a class="aqv-topbar__item" data-travessia-id="1951" href="${t.href}" aria-label="Ver informações da travessia ${t.nomes.join(' – ')}" >
  <div class="aqv-topbar__superior"> <div class="aqv-topbar__nomes"> <span>${t.nomes[0]}</span> <span>${t.nomes[1]}</span> </div>
  <div class="aqv-topbar__tempos" aria-label="Tempos de espera"> <span>${t.esperas[0]}</span> <span>${t.esperas[1]}</span> </div> </div>
  <div class="aqv-topbar__inferior"> <div class="aqv-topbar__embarcacoes" aria-label="${t.barcos} embarcações em operação">
  <img decoding="async" class="aqv-topbar__icone-balsa" src="https://acquavias.com.br/balsa_veiculos.svg" alt="" width="57" height="25">
  <span class="aqv-topbar__quantidade">${t.barcos}</span> </div>
  <img decoding="async" class="aqv-topbar__icone-clima" src="https://acquavias.com.br/nublado_dia.svg" alt="${t.clima}" title="Clima: ${t.clima}" width="52" height="52" > </div> </a>`;
const lado=(espera,clima)=>`<div class="uncode_text_column" ><span class="aqv-clima-destaque">
                <img decoding="async"
                    class="aqv-clima-destaque__icone"
                    src="https://acquavias.com.br/nublado_dia.svg"
                    alt="${clima}"
                    width="40"
                    height="40"
                >
            </span>
</div><h1><span><span class="aqv-tempo-dinamico"><span class="aqv-tempo-dinamico__rotulo">Tempo de espera:</span> <span class="aqv-tempo-dinamico__valor">${espera}</span></span></span></h1>`;

// Opções: esperas e climas do corpo da página, aviso, mudanças na barra do topo e se o corpo existe.
export function paginaAcquavias(id,{esperas,climas=['Encoberto — Dia','Parcialmente Nublado — Dia'],aviso=null,topo={},semCorpo=false}={}){
  const itens=travessiasDoTopo.map(t=>t.href===`/${id}/`?{...t,...topo}:t);
  const proprio=itens.find(t=>t.href===`/${id}/`);
  const [a,b]=esperas??proprio?.esperas??['10 min','10 min'];
  return `<!doctype html><html><body><div class="aqv-topbar" data-ajax-url="https://acquavias.com.br/wp-admin/admin-ajax.php"><div class="aqv-topbar__trilho">
${itens.map(itemDoTopo).join('\n')}
</div></div>
${aviso?`<div class="uncode_text_column" ><div class="aqv-aviso-usuario">${aviso}</div>\n</div>`:''}
${semCorpo?'':lado(a,climas[0])+lado(b,climas[1])}
</body></html>`;
}
