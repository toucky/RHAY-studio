# RHAY Auto-Tune CLEAN GOLD — prototype natif hors ligne

Cette zone est un prototype isolé. Elle ne modifie pas l'interface ou le monitoring de RHAY Studio.

Objectif : porter le traitement validé CLEAN GOLD dans Android hors ligne, puis comparer son rendu au WAV GOLD avant toute intégration dans l'APK.

## Règles figées

- traitement du clip uniquement hors ligne ;
- aucune insertion dans le monitoring ;
- une seule voix de sortie, jamais de dry/wet pour la dose ;
- Correction = quantité de déplacement de hauteur ;
- Retune Speed = vitesse de convergence vers la note cible ;
- zones non fiables conservées depuis la source ;
- pas de PSOLA ;
- pas de de-clicker utilisé pour masquer un défaut de moteur ;
- aucune livraison commerciale de Rubber Band sans respecter sa licence GPL ou obtenir une licence commerciale ;
- le WAV CLEAN GOLD validé reste la référence sonore absolue.

## Réglages Rubber Band étudiés

- Transients: Smooth
- Detector: Soft
- Phase: Laminar
- Window: Long
- Smoothing: On
- Formant: Preserved
- Pitch quality: High Consistency
- Channels: Together
- Tempo: 1.0

## ARCHITECTURE REJETÉE — NE PAS INTÉGRER

Le prototype qui crée une instance de pitch-shifter par note/région puis réinsère chaque rendu avec un crossfade est **rejeté**.

Raisons confirmées à l'écoute :

1. Le crossfade mélange temporairement audio brut et audio corrigé. Même s'il devait servir uniquement de raccord, il produit deux hauteurs/phases simultanées et donc un effet chorus/unisson.
2. Redémarrer le pitch-shifter pour chaque note réinitialise son état spectral/phase. Les raccords entre instances peuvent produire les craquements qui réapparaissent sur les notes longues.
3. Le fichier GOLD validé ne présente ni ce chorus ni ces craquements. Une architecture qui les introduit est donc invalide, même si ses mesures de pitch sont bonnes.

Conséquence : **aucun code “per-note + crossfade” ne doit être raccordé à l'APK.**

## Direction correcte à reprendre

- conserver un chemin de sortie unique ;
- aucune superposition brut/tuné pendant la correction ;
- préserver l'état de phase du moteur sur toute la zone vocale concernée ;
- reconstruire exactement la trajectoire/stratégie qui a produit le WAV GOLD, puis effectuer une comparaison A/B stricte ;
- ne modifier l'APK qu'après validation sonore sur la même brute.

## Étapes

1. Utiliser la brute et le WAV GOLD comme paire de référence.
2. Reconstituer le traitement qui produit une seule voix continue sans reset par note.
3. Rejeter automatiquement toute sortie avec chorus, doublage ou craquement avant envoi utilisateur.
4. Valider à l'écoute contre GOLD.
5. Seulement après validation, raccorder le pont Java/JNI et intégrer à l'application.
