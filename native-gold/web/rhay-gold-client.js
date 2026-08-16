/* RHAY Auto-Tune CLEAN GOLD — WebView client
 * Tracking musical en JavaScript, pitch-render natif Rubber Band hors ligne.
 * Aucun traitement n'est relié au monitoring.
 */
(function(global){
  'use strict';
  const clamp=(v,a,b)=>Math.max(a,Math.min(b,v));
  const midiFromHz=hz=>69+12*Math.log2(hz/440);
  const hzFromMidi=m=>440*Math.pow(2,(m-69)/12);
  const sleep=ms=>new Promise(r=>setTimeout(r,ms));
  function voiceRange(v){if(v==='bass')return[60,280];if(v==='tenor')return[80,450];if(v==='alto')return[110,560];if(v==='soprano')return[150,750];return[65,700];}
  function scaleSet(k,s){const a=s==='minor'?[0,2,3,5,7,8,10]:s==='chromatic'?[0,1,2,3,4,5,6,7,8,9,10,11]:[0,2,4,5,7,9,11];return new Set(a.map(v=>(v+k)%12));}
  function nearestAllowed(m,k,s){const set=scaleSet(k,s);let best=Math.round(m),bd=1e9;for(let q=Math.floor(m)-12;q<=Math.ceil(m)+12;q++){if(set.has(((q%12)+12)%12)){const d=Math.abs(q-m);if(d<bd){bd=d;best=q;}}}return best;}
  function monoFrom(b){const o=new Float32Array(b.length);for(let c=0;c<b.numberOfChannels;c++){const d=b.getChannelData(c),g=1/b.numberOfChannels;for(let i=0;i<o.length;i++)o[i]+=d[i]*g;}return o;}
  function yinPitch(x,start,size,sr,minHz,maxHz){const half=size>>1,minTau=Math.max(2,Math.floor(sr/maxHz)),maxTau=Math.min(half-2,Math.floor(sr/minHz));let rms=0;for(let i=0;i<size;i++){const v=x[start+i]||0;rms+=v*v;}rms=Math.sqrt(rms/size);if(rms<.004)return{hz:0,conf:0,rms};const d=new Float32Array(maxTau+1);for(let tau=minTau;tau<=maxTau;tau++){let sum=0;for(let j=0;j<half;j++){const z=(x[start+j]||0)-(x[start+j+tau]||0);sum+=z*z;}d[tau]=sum;}let run=0,bestTau=0,best=1e9;for(let tau=minTau;tau<=maxTau;tau++){run+=d[tau];const cm=run>0?d[tau]*tau/run:1;if(cm<best){best=cm;bestTau=tau;}if(tau>minTau+1&&cm<.12){const prev=d[tau-1]*(tau-1)/Math.max(1e-12,run-d[tau]);if(cm<=prev){best=cm;bestTau=tau;break;}}}if(!bestTau)return{hz:0,conf:0,rms};let tau=bestTau;if(tau>minTau&&tau<maxTau){const y0=d[tau-1],y1=d[tau],y2=d[tau+1],den=y0-2*y1+y2;if(Math.abs(den)>1e-12)tau+=.5*(y0-y2)/den;}return{hz:sr/tau,conf:clamp(1-best,0,1),rms};}
  async function analyze(buffer,settings={},progress=()=>{}){
    const cfg={key:7,scale:'major',voice:'tenor',tracking:40,transitionMs:40,...settings};
    const x=monoFrom(buffer),sr=buffer.sampleRate,[minHz,maxHz]=voiceRange(cfg.voice);
    const hop=Math.round(sr*.010),frame=Math.round(sr*.045),threshold=.52+(cfg.tracking/100)*.25,fs=[];
    let prev=null,octaveFixes=0,glitches=0,pendingLeap=null,pendingLeapCount=0;
    const total=Math.max(1,Math.floor((x.length-frame)/hop));
    for(let n=0,start=0;start+frame<x.length;start+=hop,n++){
      const p=yinPitch(x,start,frame,sr,minHz,maxHz);let hz=p.hz,m=hz?midiFromHz(hz):null,octFix=false;
      if(m!=null&&prev!=null&&p.conf>.52){let best=m,bd=Math.abs(m-prev);for(const cand of[m-12,m+12]){const h=hzFromMidi(cand);if(h>=minHz&&h<=maxHz){const dd=Math.abs(cand-prev);if(dd+3.5<bd){best=cand;bd=dd;octFix=true;}}}if(octFix){m=best;hz=hzFromMidi(best);octaveFixes++;}}
      let accepted=m!=null&&p.conf>=threshold,glitch=false;
      if(accepted&&prev!=null){const jump=Math.abs(m-prev);if(jump>4.5){if(pendingLeap!=null&&Math.abs(m-pendingLeap)<1.5){pendingLeap=.7*pendingLeap+.3*m;pendingLeapCount++;}else{pendingLeap=m;pendingLeapCount=1;}if(pendingLeapCount<4){accepted=false;glitch=true;glitches++;}else{m=pendingLeap;hz=hzFromMidi(m);prev=m;pendingLeap=null;pendingLeapCount=0;}}else{pendingLeap=null;pendingLeapCount=0;}}
      if(accepted)prev=prev==null?m:.7*prev+.3*m;
      fs.push({start,hz,midi:m,conf:p.conf,rms:p.rms,accepted,target:null,octFix,glitch,rawCents:0});
      if(n%24===0){progress(5+50*n/total,'Analyse hauteur '+Math.round(100*n/total)+' %');await sleep(0);}
    }
    for(let i=1;i<fs.length-1;i++){if(fs[i-1].accepted&&fs[i].accepted&&fs[i+1].accepted){const a=[fs[i-1].midi,fs[i].midi,fs[i+1].midi].sort((u,v)=>u-v);if(a[2]-a[0]<3){fs[i].midi=a[1];fs[i].hz=hzFromMidi(a[1]);}}}
    let locked=null,pending=null,count=0;const minFrames=Math.max(1,Math.round((cfg.transitionMs/1000)/(hop/sr)));
    for(const f of fs){if(!f.accepted)continue;const t=nearestAllowed(f.midi,cfg.key,cfg.scale);if(locked==null)locked=t;else if(t===locked){pending=null;count=0;}else{if(pending===t)count++;else{pending=t;count=1;}if(count>=minFrames&&Math.abs(f.midi-t)+.08<Math.abs(f.midi-locked)){locked=t;pending=null;count=0;}}if(Math.abs(locked-f.midi)>1.25)locked=t;f.target=locked;f.rawCents=(locked-f.midi)*100;if(Math.abs(f.rawCents)>125){f.accepted=false;f.glitch=true;glitches++;f.target=null;f.rawCents=0;}}
    progress(56,'Tracking musical terminé');return{frames:fs,hop,frame,sr,octaveFixes,glitches,threshold};
  }
  function median(a){if(!a.length)return 0;const x=a.slice().sort((u,v)=>u-v),m=x.length>>1;return x.length&1?x[m]:(x[m-1]+x[m])*.5;}
  function buildStableRegions(analysis,settings={}){
    const cfg={correction:100,speed:100,...settings},fs=analysis.frames,dt=analysis.hop/analysis.sr,correction=clamp(cfg.correction,0,100)/100,speed=clamp(cfg.speed,0,100)/100;
    const tau=.28*Math.pow(.008/.28,speed),alpha=speed>=.995?1:1-Math.exp(-dt/tau);let state=0;const applied=new Float32Array(fs.length);
    for(let i=0;i<fs.length;i++){const f=fs[i];if(f.accepted){const desired=clamp(f.rawCents,-125,125)*correction;state+=alpha*(desired-state);applied[i]=state;}else{state*=.7;applied[i]=0;}}
    const regions=[];let i=0;
    while(i<fs.length){if(!fs[i].accepted||fs[i].target==null){i++;continue;}const target=fs[i].target;let j=i+1,gap=0,lastGood=i;while(j<fs.length){if(fs[j].accepted&&fs[j].target===target){gap=0;lastGood=j;j++;continue;}if(gap<2){gap++;j++;continue;}break;}j=lastGood+1;const good=[];for(let q=i;q<j;q++)if(fs[q].accepted&&fs[q].target===target)good.push(applied[q]);const duration=(j-i)*analysis.hop/analysis.sr;if(good.length>=5&&duration>=.060){const cents=median(good);if(Math.abs(cents)>=1){regions.push({startFrame:fs[i].start,endFrame:fs[j-1].start+analysis.hop,targetMidi:target,cents,pitchScale:Math.pow(2,cents/1200)});}}i=Math.max(j,i+1);}
    return regions;
  }
  function bytesToBase64(bytes){let s='';const step=0x8000;for(let i=0;i<bytes.length;i+=step){const part=bytes.subarray(i,Math.min(bytes.length,i+step));s+=String.fromCharCode.apply(null,part);}return btoa(s);}
  function base64ToBytes(s){const bin=atob(s),out=new Uint8Array(bin.length);for(let i=0;i<bin.length;i++)out[i]=bin.charCodeAt(i);return out;}
  async function uploadAudioBuffer(buffer,token,progress){if(!global.RhayGold)throw new Error('Pont natif RHAY GOLD absent');const ch=buffer.numberOfChannels,sr=buffer.sampleRate,frames=buffer.length;if(!global.RhayGold.beginUpload(token,sr,ch,frames))throw new Error('Impossible de préparer le rendu natif');const src=Array.from({length:ch},(_,c)=>buffer.getChannelData(c)),chunkFrames=8192;for(let start=0;start<frames;start+=chunkFrames){const n=Math.min(chunkFrames,frames-start),inter=new Float32Array(n*ch);let p=0;for(let i=0;i<n;i++)for(let c=0;c<ch;c++)inter[p++]=src[c][start+i];const b64=bytesToBase64(new Uint8Array(inter.buffer));if(!global.RhayGold.appendUpload(b64))throw new Error('Transfert PCM interrompu');progress(58+10*(start+n)/frames,'Transfert vers moteur natif');if(((start/chunkFrames)|0)%4===0)await sleep(0);}if(!global.RhayGold.finishUpload())throw new Error('PCM natif incomplet');}
  async function waitNative(progress){while(true){let st;try{st=JSON.parse(String(global.RhayGold.status()));}catch(e){throw new Error('Statut moteur GOLD invalide');}if(st.error)throw new Error(st.error);progress(68+Math.max(0,Math.min(1,(st.progress||0)/100))*24,st.phase||'Rendu GOLD');if(st.done&&!st.busy)return st;if(!st.busy&&!st.done)throw new Error('Le moteur GOLD s\'est arrêté sans résultat');await sleep(45);}}
  async function downloadAudioBuffer(source,ctx,progress){const expected=source.length*source.numberOfChannels*4,reported=Number(global.RhayGold.resultSizeBytes());if(reported!==expected)throw new Error('Taille du rendu GOLD incorrecte');if(!global.RhayGold.resultBegin())throw new Error('Résultat GOLD indisponible');const bytes=new Uint8Array(expected);let pos=0;while(pos<expected){const s=String(global.RhayGold.resultPull(256*1024));if(!s)break;const part=base64ToBytes(s);if(pos+part.length>bytes.length)throw new Error('Résultat GOLD trop long');bytes.set(part,pos);pos+=part.length;progress(92+7*pos/expected,'Retour du rendu GOLD');await sleep(0);}if(pos!==expected)throw new Error('Résultat GOLD tronqué');const floats=new Float32Array(bytes.buffer),out=ctx.createBuffer(source.numberOfChannels,source.length,source.sampleRate),ch=source.numberOfChannels;for(let c=0;c<ch;c++){const d=out.getChannelData(c);for(let i=0,p=c;i<source.length;i++,p+=ch)d[i]=floats[p];}return out;}
  async function process(sourceBuffer,settings={},onProgress=()=>{}){
    if(!sourceBuffer||typeof sourceBuffer.getChannelData!=='function')throw new Error('AudioBuffer requis');
    if(!global.RhayGold||!global.RhayGold.isAvailable()){const why=global.RhayGold?String(global.RhayGold.engineError()||'inconnu'):'pont Java absent';throw new Error('Moteur RHAY CLEAN GOLD indisponible: '+why);}
    const cfg={key:7,scale:'major',voice:'tenor',correction:100,speed:100,tracking:40,transitionMs:40,...settings},progress=(p,t)=>{try{onProgress(p,t);}catch(e){}};
    try{
      progress(1,'Préparation Auto-Tune CLEAN GOLD');const analysis=await analyze(sourceBuffer,cfg,progress),regions=buildStableRegions(analysis,cfg);progress(57,regions.length+' région(s) vocale(s) stable(s)');
      if(!regions.length)return{buffer:sourceBuffer,analysis,regions,unchanged:true,engine:'CLEAN-GOLD-NATIVE'};
      const token='rhay_'+Date.now().toString(36)+'_'+Math.random().toString(36).slice(2,8);await uploadAudioBuffer(sourceBuffer,token,progress);if(!global.RhayGold.process(JSON.stringify(regions)))throw new Error('Le moteur natif refuse le rendu');await waitNative(progress);
      const AC=sourceBuffer._rhayContext||global.__rhayAudioContext||null,ctx=AC||(typeof global.bufCtx==='function'?global.bufCtx():null);if(!ctx||typeof ctx.createBuffer!=='function')throw new Error('Contexte audio RHAY indisponible');const out=await downloadAudioBuffer(sourceBuffer,ctx,progress);progress(100,'Auto-Tune CLEAN GOLD terminé');return{buffer:out,analysis,regions,unchanged:false,engine:'CLEAN-GOLD-NATIVE'};
    }finally{try{if(global.RhayGold){const st=JSON.parse(String(global.RhayGold.status()));if(!st.busy)global.RhayGold.clear();}}catch(e){}}
  }
  global.RhayGoldNativeClient={version:'1.0.0-GOLD-NATIVE',defaults:{key:7,scale:'major',voice:'tenor',correction:100,speed:100,tracking:40,transitionMs:40},analyze,buildStableRegions,process};
})(typeof window!=='undefined'?window:globalThis);
