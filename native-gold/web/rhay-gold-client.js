/* RHAY Auto-Tune CLEAN — WebView client Android
 *
 * Architecture v6 continue :
 * F0 -> note cible -> Retune Speed -> Humanize -> Flex Tune ->
 * UNE SEULE courbe de pitch -> UNE SEULE instance Rubber Band native.
 *
 * Aucun traitement n'est relié au monitoring. Aucun dry/wet. Aucun reset
 * du moteur par note. Aucun crossfade brut/tuné.
 */
(function(global){
  'use strict';

  const clamp=(v,a,b)=>Math.max(a,Math.min(b,v));
  const midiFromHz=hz=>69+12*Math.log2(hz/440);
  const hzFromMidi=m=>440*Math.pow(2,(m-69)/12);
  const sleep=ms=>new Promise(r=>setTimeout(r,ms));

  const SCALES={
    major:[0,2,4,5,7,9,11],
    minor:[0,2,3,5,7,8,10],
    harmonicMinor:[0,2,3,5,7,8,11],
    melodicMinor:[0,2,3,5,7,9,11],
    pentMajor:[0,2,4,7,9],
    pentMinor:[0,3,5,7,10],
    chromatic:[0,1,2,3,4,5,6,7,8,9,10,11]
  };

  function normalizeSettings(settings){
    const s=settings||{};
    return {
      key: clamp(Number.isFinite(+s.key)?+s.key:7,0,11),
      scale: SCALES[s.scale]?s.scale:'major',
      retuneSpeed: clamp(Number.isFinite(+s.retuneSpeed)?+s.retuneSpeed:(Number.isFinite(+s.speed)?+s.speed:60),0,100),
      humanize: clamp(Number.isFinite(+s.humanize)?+s.humanize:25,0,100),
      flexTune: clamp(Number.isFinite(+s.flexTune)?+s.flexTune:18,0,100),
      voice: s.voice||'auto'
    };
  }

  function speedToMs(v){
    v=clamp(v,0,100);
    return 2+200*Math.pow(1-v/100,1.8);
  }

  function voiceRange(v){
    if(v==='bass')return[55,300];
    if(v==='tenor')return[75,470];
    if(v==='alto')return[100,600];
    if(v==='soprano')return[145,780];
    return[60,750];
  }

  function scaleSet(key,scale){
    const base=SCALES[scale]||SCALES.major;
    return new Set(base.map(v=>(v+key)%12));
  }

  function nearestAllowed(midi,key,scale){
    const set=scaleSet(key,scale);
    let best=Math.round(midi),dist=1e9;
    for(let n=Math.floor(midi)-8;n<=Math.ceil(midi)+8;n++){
      if(!set.has(((n%12)+12)%12))continue;
      const d=Math.abs(n-midi);
      if(d<dist){dist=d;best=n;}
    }
    return best;
  }

  function monoFrom(buffer){
    const out=new Float32Array(buffer.length),g=1/buffer.numberOfChannels;
    for(let c=0;c<buffer.numberOfChannels;c++){
      const d=buffer.getChannelData(c);
      for(let i=0;i<out.length;i++)out[i]+=d[i]*g;
    }
    return out;
  }

  function yinPitch(x,start,size,sr,minHz,maxHz){
    const half=size>>1;
    const minTau=Math.max(2,Math.floor(sr/maxHz));
    const maxTau=Math.min(half-2,Math.floor(sr/minHz));
    let rms=0;
    for(let i=0;i<size;i++){const v=x[start+i]||0;rms+=v*v;}
    rms=Math.sqrt(rms/size);
    if(rms<.0035)return{hz:0,conf:0,rms};

    const d=new Float32Array(maxTau+1);
    for(let tau=minTau;tau<=maxTau;tau++){
      let sum=0;
      for(let j=0;j<half;j++){
        const z=(x[start+j]||0)-(x[start+j+tau]||0);
        sum+=z*z;
      }
      d[tau]=sum;
    }

    let run=0,bestTau=0,best=1e9;
    for(let tau=minTau;tau<=maxTau;tau++){
      run+=d[tau];
      const cm=run>0?d[tau]*tau/run:1;
      if(cm<best){best=cm;bestTau=tau;}
      if(tau>minTau+1&&cm<.12){
        const prev=d[tau-1]*(tau-1)/Math.max(1e-12,run-d[tau]);
        if(cm<=prev){best=cm;bestTau=tau;break;}
      }
    }
    if(!bestTau)return{hz:0,conf:0,rms};

    let tau=bestTau;
    if(tau>minTau&&tau<maxTau){
      const y0=d[tau-1],y1=d[tau],y2=d[tau+1],den=y0-2*y1+y2;
      if(Math.abs(den)>1e-12)tau+=.5*(y0-y2)/den;
    }
    return{hz:sr/tau,conf:clamp(1-best,0,1),rms};
  }

  async function analyze(buffer,settings={},progress=()=>{}){
    const cfg=normalizeSettings(settings);
    const x=monoFrom(buffer),sr=buffer.sampleRate,[minHz,maxHz]=voiceRange(cfg.voice);
    const hop=Math.max(1,Math.round(sr*.010));
    const frame=Math.max(hop*2,Math.round(sr*.045));
    const threshold=.60;
    const frames=[];
    const total=Math.max(1,Math.floor((x.length-frame)/hop));

    let prevMidi=null,octaveFixes=0,glitches=0;
    let pendingLeap=null,pendingLeapCount=0;

    for(let n=0,start=0;start+frame<x.length;start+=hop,n++){
      const p=yinPitch(x,start,frame,sr,minHz,maxHz);
      let hz=p.hz,midi=hz?midiFromHz(hz):null,octFix=false;

      // Continuité anti-octave : choisir la fondamentale cohérente avec le passé.
      if(midi!=null&&prevMidi!=null&&p.conf>.52){
        let best=midi,bd=Math.abs(midi-prevMidi);
        for(const cand of[midi-12,midi+12]){
          const h=hzFromMidi(cand);
          if(h>=minHz&&h<=maxHz){
            const dd=Math.abs(cand-prevMidi);
            if(dd+3.5<bd){best=cand;bd=dd;octFix=true;}
          }
        }
        if(octFix){midi=best;hz=hzFromMidi(best);octaveFixes++;}
      }

      let accepted=midi!=null&&p.conf>=threshold,glitch=false;
      if(accepted&&prevMidi!=null){
        const jump=Math.abs(midi-prevMidi);
        if(jump>5.0){
          if(pendingLeap!=null&&Math.abs(midi-pendingLeap)<1.5){
            pendingLeap=.7*pendingLeap+.3*midi;pendingLeapCount++;
          }else{pendingLeap=midi;pendingLeapCount=1;}
          if(pendingLeapCount<3){accepted=false;glitch=true;glitches++;}
          else{midi=pendingLeap;hz=hzFromMidi(midi);pendingLeap=null;pendingLeapCount=0;}
        }else{pendingLeap=null;pendingLeapCount=0;}
      }
      if(accepted)prevMidi=prevMidi==null?midi:.72*prevMidi+.28*midi;

      frames.push({
        start,hz,midi,conf:p.conf,rms:p.rms,accepted,glitch,octFix,
        candidate:null,target:null,rawSemis:0,noteAgeMs:0
      });

      if(n%28===0){
        progress(5+42*n/total,'Analyse hauteur '+Math.round(100*n/total)+' %');
        await sleep(0);
      }
    }

    // Médiane locale, uniquement quand trois fondamentales sont cohérentes.
    for(let i=1;i<frames.length-1;i++){
      const a=frames[i-1],b=frames[i],c=frames[i+1];
      if(a.accepted&&b.accepted&&c.accepted){
        const m=[a.midi,b.midi,c.midi].sort((u,v)=>u-v);
        if(m[2]-m[0]<2.5){b.midi=m[1];b.hz=hzFromMidi(m[1]);}
      }
    }

    // Flex Tune contrôle la facilité avec laquelle une brève excursion devient
    // une nouvelle note cible. Il ne modifie pas la Retune Speed.
    const flex=cfg.flexTune/100;
    const switchMs=20+flex*90;
    const minFrames=Math.max(1,Math.round(switchMs/(hop/sr*1000)));
    const switchMargin=.05+.12*flex;

    let locked=null,pending=null,count=0,noteAge=0,unvoiced=999;
    for(const f of frames){
      if(!f.accepted){
        unvoiced+=hop/sr*1000;
        if(unvoiced>120){locked=null;pending=null;count=0;noteAge=0;}
        continue;
      }
      unvoiced=0;
      const cand=nearestAllowed(f.midi,cfg.key,cfg.scale);
      f.candidate=cand;

      if(locked==null){
        locked=cand;pending=null;count=0;noteAge=0;
      }else if(cand===locked){
        pending=null;count=0;noteAge+=hop/sr*1000;
      }else{
        const currentDist=Math.abs(f.midi-locked);
        const candidateDist=Math.abs(f.midi-cand);
        const largeMove=currentDist>1.30;
        if(pending===cand)count++;else{pending=cand;count=1;}
        if(largeMove || (count>=minFrames&&candidateDist+switchMargin<currentDist)){
          locked=cand;pending=null;count=0;noteAge=0;
        }else{
          noteAge+=hop/sr*1000;
        }
      }

      f.target=locked;
      f.noteAgeMs=noteAge;
      f.rawSemis=clamp(locked-f.midi,-1.5,1.5);
    }

    progress(48,'Tracking musical terminé');
    return{frames,hop,frame,sr,octaveFixes,glitches,threshold,switchMs};
  }

  function median(values){
    if(!values.length)return 0;
    const x=values.slice().sort((a,b)=>a-b),m=x.length>>1;
    return x.length&1?x[m]:(x[m-1]+x[m])*.5;
  }

  function buildPitchCurve(buffer,analysis,settings={}){
    const cfg=normalizeSettings(settings);
    const dtMs=analysis.hop/analysis.sr*1000;
    const baseTauMs=speedToMs(cfg.retuneSpeed);
    const human=cfg.humanize/100;
    const flex=cfg.flexTune/100;
    const deadZone=0.45*flex; // 0..45 cents autour de la note cible

    const frameShift=new Float32Array(analysis.frames.length);
    let state=0,unvoicedMs=999;

    for(let i=0;i<analysis.frames.length;i++){
      const f=analysis.frames[i];
      if(!f.accepted||f.target==null){
        unvoicedMs+=dtMs;
        const releaseAlpha=1-Math.exp(-dtMs/70);
        state+=releaseAlpha*(0-state);
        if(unvoicedMs>160&&Math.abs(state)<.002)state=0;
        frameShift[i]=state;
        continue;
      }
      unvoicedMs=0;

      let delta=f.rawSemis;
      const sign=Math.sign(delta),mag=Math.abs(delta);
      delta=mag<=deadZone?0:sign*(mag-deadZone);

      // Humanize agit sur les notes longues : la convergence devient plus lente
      // et un peu moins rigide, ce qui laisse vivre le vibrato naturel sans
      // créer une seconde voix.
      const sustain=clamp((f.noteAgeMs-300)/700,0,1);
      const tauMs=baseTauMs*(1+6*human*sustain);
      const desired=delta*(1-.35*human*sustain);
      const alpha=1-Math.exp(-dtMs/Math.max(1,tauMs));
      state+=alpha*(desired-state);
      frameShift[i]=clamp(state,-1.5,1.5);
    }

    // Une commande toutes les 60 ms : assez réactive pour la mélodie mais plus
    // stable qu'une commande à chaque micro-frame, ce qui réduit le chorus.
    const controlMs=60;
    const controlFrames=Math.max(1,Math.round(buffer.sampleRate*controlMs/1000));
    const blockCount=Math.max(1,Math.ceil(buffer.length/controlFrames));
    const pitchScales=new Array(blockCount);
    const hopSec=analysis.hop/analysis.sr;

    for(let b=0;b<blockCount;b++){
      const t0=b*controlFrames/buffer.sampleRate;
      const t1=(b+1)*controlFrames/buffer.sampleRate;
      const i0=Math.max(0,Math.floor(t0/hopSec));
      const i1=Math.min(frameShift.length,Math.max(i0+1,Math.ceil(t1/hopSec)));
      const vals=[];
      for(let i=i0;i<i1;i++)vals.push(frameShift[i]);
      const semis=clamp(median(vals),-1.5,1.5);
      pitchScales[b]=Math.pow(2,semis/12);
    }

    return{
      controlMs,
      controlFrames,
      pitchScales,
      retuneMs:baseTauMs,
      settings:cfg
    };
  }

  function bytesToBase64(bytes){
    let s='';const step=0x8000;
    for(let i=0;i<bytes.length;i+=step){
      const part=bytes.subarray(i,Math.min(bytes.length,i+step));
      s+=String.fromCharCode.apply(null,part);
    }
    return btoa(s);
  }

  function base64ToBytes(s){
    const bin=atob(s),out=new Uint8Array(bin.length);
    for(let i=0;i<bin.length;i++)out[i]=bin.charCodeAt(i);
    return out;
  }

  async function uploadAudioBuffer(buffer,token,progress){
    if(!global.RhayGold)throw new Error('Pont natif RHAY Auto-Tune absent');
    const ch=buffer.numberOfChannels,sr=buffer.sampleRate,frames=buffer.length;
    if(!global.RhayGold.beginUpload(token,sr,ch,frames))throw new Error('Impossible de préparer le rendu natif');
    const src=Array.from({length:ch},(_,c)=>buffer.getChannelData(c));
    const chunkFrames=8192;
    for(let start=0;start<frames;start+=chunkFrames){
      const n=Math.min(chunkFrames,frames-start),inter=new Float32Array(n*ch);
      let p=0;
      for(let i=0;i<n;i++)for(let c=0;c<ch;c++)inter[p++]=src[c][start+i];
      const b64=bytesToBase64(new Uint8Array(inter.buffer));
      if(!global.RhayGold.appendUpload(b64))throw new Error('Transfert PCM interrompu');
      progress(50+12*(start+n)/frames,'Transfert vers moteur natif');
      if(((start/chunkFrames)|0)%4===0)await sleep(0);
    }
    if(!global.RhayGold.finishUpload())throw new Error('PCM natif incomplet');
  }

  async function waitNative(progress){
    while(true){
      let st;
      try{st=JSON.parse(String(global.RhayGold.status()));}
      catch(e){throw new Error('Statut moteur Auto-Tune invalide');}
      if(st.error)throw new Error(st.error);
      progress(64+Math.max(0,Math.min(1,(st.progress||0)/100))*27,st.phase||'Rendu Auto-Tune');
      if(st.done&&!st.busy)return st;
      if(!st.busy&&!st.done)throw new Error('Le moteur Auto-Tune s\'est arrêté sans résultat');
      await sleep(45);
    }
  }

  async function downloadAudioBuffer(source,ctx,progress){
    const expected=source.length*source.numberOfChannels*4;
    const reported=Number(global.RhayGold.resultSizeBytes());
    if(reported!==expected)throw new Error('Taille du rendu Auto-Tune incorrecte');
    if(!global.RhayGold.resultBegin())throw new Error('Résultat Auto-Tune indisponible');
    const bytes=new Uint8Array(expected);let pos=0;
    while(pos<expected){
      const s=String(global.RhayGold.resultPull(256*1024));
      if(!s)break;
      const part=base64ToBytes(s);
      if(pos+part.length>bytes.length)throw new Error('Résultat Auto-Tune trop long');
      bytes.set(part,pos);pos+=part.length;
      progress(91+8*pos/expected,'Retour du rendu Auto-Tune');
      await sleep(0);
    }
    if(pos!==expected)throw new Error('Résultat Auto-Tune tronqué');
    const floats=new Float32Array(bytes.buffer);
    const out=ctx.createBuffer(source.numberOfChannels,source.length,source.sampleRate),ch=source.numberOfChannels;
    for(let c=0;c<ch;c++){
      const d=out.getChannelData(c);
      for(let i=0,p=c;i<source.length;i++,p+=ch)d[i]=floats[p];
    }
    return out;
  }

  async function process(sourceBuffer,settings={},onProgress=()=>{}){
    if(!sourceBuffer||typeof sourceBuffer.getChannelData!=='function')throw new Error('AudioBuffer requis');
    if(!global.RhayGold||!global.RhayGold.isAvailable()){
      const why=global.RhayGold?String(global.RhayGold.engineError()||'inconnu'):'pont Java absent';
      throw new Error('Moteur RHAY Auto-Tune natif indisponible: '+why);
    }

    const cfg=normalizeSettings(settings);
    const progress=(p,t)=>{try{onProgress(p,t);}catch(e){}};

    try{
      progress(1,'Préparation Auto-Tune');
      const analysis=await analyze(sourceBuffer,cfg,progress);
      progress(49,'Construction Retune Speed / Humanize / Flex Tune');
      const curve=buildPitchCurve(sourceBuffer,analysis,cfg);
      await sleep(0);

      const token='rhay_'+Date.now().toString(36)+'_'+Math.random().toString(36).slice(2,8);
      await uploadAudioBuffer(sourceBuffer,token,progress);

      const payload=JSON.stringify({
        controlFrames:curve.controlFrames,
        pitchScales:curve.pitchScales
      });
      const ok=global.RhayGold.processCurve?
        global.RhayGold.processCurve(payload):global.RhayGold.process(payload);
      if(!ok)throw new Error('Le moteur natif refuse le rendu');

      await waitNative(progress);
      const AC=sourceBuffer._rhayContext||global.__rhayAudioContext||null;
      const ctx=AC||(typeof global.bufCtx==='function'?global.bufCtx():null);
      if(!ctx||typeof ctx.createBuffer!=='function')throw new Error('Contexte audio RHAY indisponible');
      const out=await downloadAudioBuffer(sourceBuffer,ctx,progress);
      progress(100,'Auto-Tune terminé');

      return{
        buffer:out,
        analysis,
        curve,
        settings:cfg,
        diagnostics:{
          octaveFixes:analysis.octaveFixes,
          glitches:analysis.glitches,
          retuneMs:curve.retuneMs,
          controlMs:curve.controlMs
        },
        engine:'RUBBERBAND-R3-CONTINUOUS'
      };
    }finally{
      try{
        if(global.RhayGold){
          const st=JSON.parse(String(global.RhayGold.status()));
          if(!st.busy)global.RhayGold.clear();
        }
      }catch(e){}
    }
  }

  global.RhayGoldNativeClient={
    version:'2.0.0-CONTINUOUS-V6',
    defaults:{key:7,scale:'major',retuneSpeed:60,humanize:25,flexTune:18,voice:'auto'},
    speedToMs,
    analyze,
    buildPitchCurve,
    process
  };
})(typeof window!=='undefined'?window:globalThis);
