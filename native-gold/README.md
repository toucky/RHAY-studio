# RHAY Auto-Tune CLEAN GOLD — prototype natif hors ligne

Cette zone est un prototype isolé. Elle ne modifie pas l'interface ou le monitoring de RHAY Studio.

Objectif : porter le moteur de pitch validé CLEAN GOLD dans un traitement natif Android hors ligne, puis comparer son rendu au WAV GOLD avant toute intégration dans l'APK.

## Règles figées

- traitement du clip uniquement hors ligne ;
- aucune insertion dans le monitoring ;
- une seule voix de sortie, jamais de dry/wet pour la dose ;
- Correction = quantité de déplacement de hauteur ;
- Retune Speed = vitesse de convergence vers la note cible ;
- zones non fiables conservées depuis la source ;
- pas de PSOLA ;
- pas de de-clicker utilisé pour masquer un défaut de moteur ;
- moteur de pitch : Rubber Band avec réglages GOLD ;
- aucune livraison commerciale de Rubber Band sans respecter sa licence GPL ou obtenir une licence commerciale.

## Réglages moteur GOLD utilisés pour le prototype

- Transients: Smooth
- Detector: Soft
- Phase: Laminar
- Window: Long
- Smoothing: On
- Formant: Preserved
- Pitch quality: High Consistency
- Channels: Together
- Tempo: 1.0

Le moteur recevra des régions vocales stables et une translation de hauteur par région. Chaque région est traitée avec contexte, puis réinsérée avec raccord de synthèse. Ce raccord n'est pas un dry/wet de correction : il sert uniquement à assurer la continuité entre audio original et rendu.

## Étapes

1. Valider le wrapper natif sur la même brute que le GOLD.
2. Comparer notes longues et absence de doublage/craquement.
3. Ajouter le pont Java/JNI.
4. Ajouter le transfert de clip hors ligne depuis la WebView.
5. Seulement après validation, intégrer à la branche de l'application.
