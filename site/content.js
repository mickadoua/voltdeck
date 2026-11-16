/* DRAFT copy: Léa replaces the texts. Facts taken from the project README (no figures invented). */
window.SITE = {
  name: 'Voltdeck', logo: 'logo.png',
  repo: 'https://github.com/mickadoua/voltdeck',
  dmcPage: { en: 'https://www.dm-consulting.tech/en/open-source/voltdeck', fr: 'https://www.dm-consulting.tech/fr/open-source/voltdeck' },
  common: { license: 'Apache-2.0', licenseUrl: 'https://github.com/mickadoua/voltdeck/blob/main/LICENSE' },
  // APK of the latest release; the SHA-256 is the one in voltdeck.apk.sha256 of that release.
  download: { apk: { url: 'https://www.dm-consulting.tech/downloads/voltdeck.apk', sha256: 'ebb1554c11a956ea250bd8fd19dfa6d513c8fb66878105d1297792d72472500d', size: '' } },
  shots: [{ src: 'accueil.png' }, { src: 'stats-jour.png' }, { src: 'stats-semaine.png' }, { src: 'accueil-clair.png' }],
  i18n: {
    en: { tagline: 'Read-only dashboard for 12/24 V systems', eyebrow: 'Android app · Apache-2.0',
      h1: 'One dashboard for your <em>whole electrical system.</em>',
      lede: 'A read-only mobile dashboard for vans, boats and off-grid sites. It brings the Bluetooth readings of BMS batteries, solar controllers, DC-DC chargers and shunts into one app, whatever the brand.',
      shotCaptions: ['Overview', 'Daily statistics', 'Weekly statistics', 'Overview, light theme'],
      shotNote: 'Demo mode screenshots: simulated values. The app interface is in French.',
      featuresKicker: 'Features', featuresTitle: 'How much did I make, use, and how long will I last?', featuresIntro: 'Voltdeck combines every device into one view, keeps the history on the phone and never sends a command to your equipment.',
      features: [
        { icon: 'gauge', title: 'GX-style overview', text: 'Solar production, battery state, calculated consumption and estimated runtime.' },
        { icon: 'chart', title: 'Statistics', text: 'Energy per hour, day, week and month, with comparison to the previous period.' },
        { icon: 'lock', title: 'Read-only, by design', text: 'Four locks in the code and the build: no write to devices, no INTERNET permission.' }],
      highlights: { kicker: 'Details', title: 'Built to be trusted.', intro: 'Everything stays on the phone.',
        chips: ['Victron', 'JBD BMS', 'Demo mode', 'Light and dark'],
        checks: [['No network.', 'The build fails if the INTERNET permission appears.'], ['Keys stay local.', 'Victron keys live in the Android Keystore.'], ['Honest gaps.', 'A period without readings stays empty, never zero.']],
        facts: [['Platform', 'Android'], ['Devices', 'Victron, JBD'], ['License', 'Apache-2.0']] },
      downloadTitle: 'Get Voltdeck', downloadIntro: 'Install the signed APK on an Android phone, after checking its SHA-256.',
      downloadNote: 'Drivers have not been validated on real hardware yet.' },
    fr: { tagline: 'Tableau de bord en lecture seule pour installations 12/24 V', eyebrow: 'Appli Android · Apache-2.0',
      h1: 'Un seul tableau de bord pour <em>toute votre installation.</em>',
      lede: 'Un tableau de bord mobile en lecture seule pour fourgons, bateaux et sites isolés. Il réunit les mesures Bluetooth des batteries à BMS, régulateurs solaires, DC-DC et shunts, quelle que soit la marque.',
      shotCaptions: ['Vue d’ensemble', 'Statistiques du jour', 'Statistiques de la semaine', 'Vue d’ensemble, thème clair'],
      shotNote: 'Captures en mode démo : valeurs simulées. L’interface de l’appli est en français.',
      featuresKicker: 'Fonctions', featuresTitle: 'Combien produit, combien consommé, combien de temps tiendrai-je ?', featuresIntro: 'Voltdeck réunit tous les appareils dans une seule vue, garde l’historique sur le téléphone et n’envoie jamais de commande à votre matériel.',
      features: [
        { icon: 'gauge', title: 'Vue style GX', text: 'Production solaire, état de la batterie, consommation calculée et autonomie estimée.' },
        { icon: 'chart', title: 'Statistiques', text: 'Énergie par heure, jour, semaine et mois, avec comparaison à la période précédente.' },
        { icon: 'lock', title: 'Lecture seule, par construction', text: 'Quatre verrous dans le code et le build : aucune écriture vers les appareils, aucune permission INTERNET.' }],
      highlights: { kicker: 'Détails', title: 'Conçu pour inspirer confiance.', intro: 'Tout reste sur le téléphone.',
        chips: ['Victron', 'BMS JBD', 'Mode démo', 'Clair et sombre'],
        checks: [['Aucun réseau.', 'Le build échoue si la permission INTERNET apparaît.'], ['Clés en local.', 'Les clés Victron restent dans l’Android Keystore.'], ['Trous assumés.', 'Une période sans mesure reste vide, jamais à zéro.']],
        facts: [['Plateforme', 'Android'], ['Appareils', 'Victron, JBD'], ['Licence', 'Apache-2.0']] },
      downloadTitle: 'Obtenir Voltdeck', downloadIntro: 'Installez l’APK signé sur un téléphone Android, après avoir vérifié sa somme SHA-256.',
      downloadNote: 'Les pilotes n’ont pas encore été validés sur du matériel réel.' }
  }
};
