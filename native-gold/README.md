# RHAY Auto-Tune CLEAN — prototype natif Android hors ligne

Cette zone est un prototype isolé. Elle ne modifie pas l'interface ou le monitoring de RHAY Studio tant que le rendu n'est pas validé dans l'APK.

Objectif : porter le traitement Auto-Tune CLEAN validé dans Android hors ligne avec une seule voix de sortie et une seule instance Rubber Band continue.

## Référence sonore

Le WAV CLEAN GOLD validé reste la référence absolue pour le timbre et l'absence de craquement/doublage.

Réglage actuel retenu après les écoutes :

- **Key / Scale** : réglables ;
- **Retune Speed** : **60 %** par défaut, environ **40 ms** dans le moteur RHAY ;
- **Humanize** : **25 %** par défaut pour laisser vivre les notes longues et le vibrato naturel ;
- **Flex Tune** : **18 %** par défaut pour séparer la sensibilité aux changements de note de la vitesse de correction ;
- Tracking non exposé dans l'interface : il reste interne au moteur.

Les noms de réglages visibles reprennent ceux familiers aux utilisateurs d'Auto-Tune : **Key, Scale, Retune Speed, Humanize, Flex Tune**.

## Règles figées

- traitement du clip uniquement hors ligne ;
- aucune insertion dans le monitoring ;
- une seule voix de sortie, jamais de dry/wet ;
- Retune Speed = rapidité de convergence vers la note cible ;
- Humanize = relâchement/ralentissement de la correction sur les notes longues pour conserver le vibrato naturel ;
- Flex Tune = tolérance aux petites variations et sensibilité au changement de note, sans modifier la Retune Speed ;
- zones non fiables conservées proches de la source ;
- anti-octave et continuité F0 avant correction ;
- pas de PSOLA ;
- pas de de-clicker utilisé pour masquer un défaut de moteur ;
- pas de post-traitement spectral agressif qui donne un effet vocodeur ;
- même durée et même alignement temporel que la source ;
- aucune livraison commerciale de Rubber Band sans respecter sa licence GPL ou obtenir une licence commerciale.

## Architecture rejetée — NE PAS RÉUTILISER

Le prototype **per-note + crossfade** est définitivement rejeté.

Il créait une instance Rubber Band par région/note puis mélangeait temporairement brut et tuné au raccord. À l'écoute cela a produit :

1. chorus / doublage unisson ;
2. réinitialisations de phase entre les notes ;
3. retour de craquements sur certaines tenues.

Aucun code de cette architecture ne doit être raccordé à l'APK.

## Architecture actuelle — CONTINUE V6

Chaîne :

**Audio brut → F0 → anti-octave → note cible Key/Scale → Flex Tune → Retune Speed → Humanize → courbe de pitch stable → une seule instance Rubber Band R3 → buffer final unique**

Points importants :

- Rubber Band **3.3.0** ;
- **Engine Finer / R3** ;
- **ProcessRealTime** utilisé uniquement pour permettre une hauteur variable pendant un rendu hors ligne ;
- **PitchHighConsistency** ;
- **FormantPreserved** ;
- **ChannelsTogether** pour la cohérence stéréo ;
- courbe de commande environ toutes les **60 ms** afin de limiter la modulation/chorus ;
- traitement interne par petits blocs, mais **une seule instance** du moteur pour tout le clip ;
- compensation explicite de `getPreferredStartPad()` et `getStartDelay()` ;
- aucun crossfade audio entre régions ;
- aucun reset par note.

## Fichiers principaux

- `src/rhay_gold_engine.cpp` : rendu Rubber Band continu + compensation délai ;
- `src/rhay_gold_jni.cpp` : JNI pour courbe variable ;
- `java/.../GoldEngine.java` : API Java ;
- `java/.../GoldWebBridge.java` : transfert WebView ↔ moteur natif, sans AudioRecord/AudioTrack ;
- `web/rhay-gold-client.js` : tracking musical et réglages Key/Scale/Retune Speed/Humanize/Flex Tune.

## Étapes avant APK final

1. compiler les bibliothèques Android arm64-v8a et armeabi-v7a ;
2. connecter `GoldWebBridge` à la WebView sous le nom `RhayGold` ;
3. remplacer le vieux moteur Auto-Tune v2.4/PSOLA de l'interface par `RhayGoldNativeClient` ;
4. garder toutes les fonctions micro, monitoring, 7-bips, mix, master, projet et export intactes ;
5. faire un rendu Android de la même brute et comparer au GOLD / v6 ;
6. seulement après validation sonore, livrer l'APK final.
