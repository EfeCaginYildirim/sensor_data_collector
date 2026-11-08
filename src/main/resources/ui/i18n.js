// Sensor Data Collector — translations and locale-aware formatting.
// The language is chosen in the app and saved with the settings; the computer's and the
// browser's language are deliberately ignored, so the app looks the same on every machine.
"use strict";

const I18N = (() => {
  const LOCALES = { en: "en-US", fr: "fr-FR" };

  const STRINGS = {
    en: {
      "nav.sections": "Sections",
      "lang.label": "Language",
      "lang.failed": "The language could not be saved.",
      "tab.monitors": "Monitors",
      "tab.collect": "Collect",
      "tab.data": "Data",
      "tab.settings": "Settings",
      "action.collect": "Collect now",
      "time.never": "never",
      "time.utc": "{time} UTC",
      "notice.notRunning": "The app is not running. Start it again to continue.",
      "notice.signedOut": "This window is not signed in. Close it and start the app again; it opens a signed-in window.",

      "status.demo": "Demo data",
      "status.api": "Vendor API",
      "status.next": "next collection {time}",
      "status.off": "daily schedule off",
      "status.line": "{source}, {schedule}. Version {version}",
      "status.dataDir": "Settings and data are kept in {dir}",
      "status.stopped": "Stopped",

      "monitors.empty": "No data yet. Collect once to fetch the history of every configured monitor.",
      "monitors.lede": "{count} monitors, data collected until {time}. Select one to plot its trend.",
      "monitors.unassigned": "Unassigned",
      "monitor.name": "Monitor {id}",
      "monitor.health": "health",
      "monitor.until": "Until {time}",
      "monitor.notYet": "Not collected yet",
      "monitor.samples": "{count} samples",
      "legend.label": "Velocity against the monitor's thresholds:",
      "zone.normal": "Normal",
      "zone.elevated": "Elevated",
      "zone.caution": "Caution",
      "zone.warning": "Warning",
      "strip.none": "No velocity reading",
      "strip.reading": ": {value} mm/s, warning at {warning}",
      "detail.left": "Left axis",
      "detail.right": "Right axis",
      "picker.none": "None",
      "chart.averaged": "{rows} samples, averaged into {points} points for display.",
      "chart.samples": "{rows} samples.",
      "chart.time": "Time",

      "collect.lede": "Each run fetches everything since the last collected sample, plus a few days of overlap for late data.",
      "collect.idle": "Idle",
      "collect.meter": "Collection progress",
      "collect.current": "Current run",
      "collect.report": "Report of the last run",
      "collect.history": "History",
      "collect.busy": "Collecting…",
      "collect.progress": "Collecting: {done} of {total} time ranges fetched",
      "collect.last": "Last run: {status}",
      "collect.noneYet": "No run yet in this session",
      "collect.fetching": "Fetching",
      "collect.waiting": "Waiting",
      "collect.chunksFailed": "{count} failed",
      "col.monitor": "Monitor",
      "col.state": "State",
      "col.timeRanges": "Time ranges",
      "col.note": "Note",
      "col.started": "Started",
      "col.result": "Result",
      "col.duration": "Duration",
      "col.newRows": "New rows",
      "col.problems": "Problems",
      "col.from": "From",
      "col.to": "To",
      "col.reason": "Reason",
      "col.actions": "Actions",
      "runs.failed": "{failed} of {total}",
      "runs.none": "none",
      "runs.seconds": "{seconds} s",
      "result.SUCCESS": "Success",
      "result.PARTIAL": "Partly collected",
      "result.FAILED": "Failed",
      "result.INVALID_SETTINGS": "Settings incomplete",
      "result.COLLECTED": "Collected",
      "result.UP_TO_DATE": "Up to date",

      "data.lede": "Choose the monitors, columns and rows to export. The collected data is never changed; filters apply to the preview and the CSV file only.",
      "data.empty": "No data yet. Collect once, then come back to choose what to export.",
      "presets.saved": "Saved selections",
      "presets.none": "None saved",
      "presets.load": "Load",
      "presets.delete": "Delete",
      "presets.saveAs": "Save current as",
      "presets.placeholder": "e.g. Pump 2 for MATLAB",
      "presets.save": "Save",
      "filters.monitors": "Monitors",
      "filters.range": "Time range",
      "filters.rangeHint": "UTC, both days included",
      "filters.from": "From",
      "filters.to": "To",
      "filters.columns": "Columns",
      "filters.measurement": "Measurement",
      "filters.all": "Select all",
      "filters.none": "Clear",
      "filters.rows": "Rows",
      "filters.addRule": "Add an exclusion rule",
      "filters.sampling": "Sampling",
      "sampling.raw": "Every sample",
      "sampling.hourly": "Hourly means",
      "sampling.daily": "Daily means",
      "filters.dropIncomplete": "Drop rows with a missing value",
      "filters.changeOnly": "Only keep rows that changed",
      "filters.byMore": "by more than",
      "filters.unit": "Unit",
      "filters.percent": "percent",
      "filters.absolute": "in absolute value",
      "filters.changeHint": "In any column, since the last kept row. Each monitor's first and last rows are always kept.",
      "filters.manual": "Leave out manually excluded ranges",
      "rule.lead": "Leave out rows where",
      "rule.column": "Column",
      "rule.comparison": "Comparison",
      "rule.gt": "is above",
      "rule.gte": "is at least",
      "rule.lt": "is below",
      "rule.lte": "is at most",
      "rule.value": "Value",
      "rule.remove": "Remove",
      "funnel.title": "What the export will contain",
      "funnel.read": "Samples in the time range",
      "funnel.manual": "In manually excluded ranges",
      "funnel.rule": "Matching the rule",
      "funnel.rules": "Matching a rule",
      "funnel.hourly": "Averaged into hourly rows",
      "funnel.daily": "Averaged into daily rows",
      "funnel.incomplete": "With a missing value",
      "funnel.unchanged": "Unchanged since the last kept row",
      "funnel.result": "Rows to export, {count} columns each",
      "dataChart.title": "Check the result on a chart",
      "dataChart.monitor": "Monitor",
      "dataChart.column": "Column",
      "dataChart.pick": "Select a monitor and a column to see the result on a chart.",
      "dataChart.noRows": "No rows left for this monitor. ",
      "dataChart.averaged": "{rows} rows, averaged into {points} points for display. ",
      "dataChart.rows": "{rows} rows. ",
      "dataChart.drag": "Drag across the chart to exclude a time range.",
      "dataChart.dragHatched": "Drag across the chart to exclude a time range; hatched ranges are already excluded.",
      "exclude.reason": "Reason",
      "exclude.placeholder": "e.g. Sensor remounted after maintenance",
      "exclude.submit": "Exclude these samples",
      "exclude.cancel": "Cancel",
      "exclude.range": "Exclude {monitor} from {from} to {to}.",
      "exclusions.caption": "Manually excluded ranges",
      "exclusions.include": "Include again",
      "exclusions.empty": "None for the selected monitors. Drag across the chart to exclude a range, for example while a sensor was being serviced.",
      "export.title": "Export",
      "export.format": "Format",
      "export.comma": "Comma separated, decimal point",
      "export.semicolon": "Semicolon separated, decimal comma (Excel in Turkish, French, German)",
      "export.tab": "Tab separated, decimal point",
      "export.download": "Download CSV",
      "export.preparing": "Preparing the file…",
      "export.done": "Downloaded {name} ({size} KB).",
      "export.failed": "The download failed. Check that the app is still running.",
      "preview.caption": "Preview",
      "preview.time": "Time (UTC)",
      "preview.monitor": "Monitor",
      "preview.empty": "Preview: no rows left",
      "preview.first": "Preview: first {shown} of {total} rows",
      "preview.all": "Preview: all {total} rows",

      "settings.source": "Data source",
      "settings.demo": "Demo",
      "settings.demoText": "Synthetic motors served by a built-in fake vendor API. Nothing to configure.",
      "settings.api": "Vendor API",
      "settings.apiText": "Collect from your condition-monitoring account.",
      "settings.scenario": "Demo scenario",
      "settings.motors": "Motors",
      "settings.days": "Days of history",
      "settings.seed": "Random seed",
      "settings.baseUrl": "Base URL",
      "settings.username": "Username",
      "settings.password": "Password",
      "settings.plant": "Plant id",
      "settings.monitorIds": "Monitor ids",
      "settings.firstDay": "First day to collect",
      "settings.parallel": "Parallel requests",
      "settings.schedule": "Schedule",
      "settings.daily": "Collect every day while the app is open",
      "settings.time": "Time",
      "settings.zone": "Time zone",
      "settings.save": "Save settings",
      "settings.test": "Test connection",
      "settings.passwordSaved": "Saved. Leave empty to keep it",
      "settings.fix": "Fix these before saving:",
      "settings.saved": "Settings saved.",
      "settings.notSaved": "Not saved.",
      "settings.testing": "Testing…",
      "quit.text": "Collection stops when the app is closed. Data and settings stay in the folder above.",
      "quit.button": "Quit app",
      "quit.done": "The app has stopped. You can close this tab.",

      "feature.KURTACCL": "Kurtosis",
      "feature.PPACCL": "Peak-to-peak acceleration",
      "feature.CRESTACCL": "Crest factor",
      "feature.GRMSACCL": "RMS acceleration (g)",
      "feature.PPSW": "Shock-wave peak-to-peak",
      "feature.SWRMS": "Shock-wave RMS",
      "feature.VRMS": "RMS velocity (mm/s)",
      "feature.IDLE": "Idle fraction",
      "feature.RPM": "Speed (rpm)",
      "feature.BPFI": "BPFI amplitude (inner race)",
      "feature.BPFO": "BPFO amplitude (outer race)",
      "feature.BSF": "BSF amplitude (ball spin)",
      "feature.FTF": "FTF amplitude (cage)",
      "feature.LOAD": "Load (%)",
      "feature.TA": "Temperature",
    },

    fr: {
      "nav.sections": "Sections",
      "lang.label": "Langue",
      "lang.failed": "La langue n'a pas pu être enregistrée.",
      "tab.monitors": "Capteurs",
      "tab.collect": "Collecte",
      "tab.data": "Données",
      "tab.settings": "Paramètres",
      "action.collect": "Collecter maintenant",
      "time.never": "jamais",
      "time.utc": "{time} UTC",
      "notice.notRunning": "L'application ne fonctionne plus. Relancez-la pour continuer.",
      "notice.signedOut": "Cette fenêtre n'est pas connectée. Fermez-la et relancez l'application ; elle ouvrira une fenêtre connectée.",

      "status.demo": "Données de démo",
      "status.api": "API fournisseur",
      "status.next": "prochaine collecte {time}",
      "status.off": "collecte quotidienne désactivée",
      "status.line": "{source}, {schedule}. Version {version}",
      "status.dataDir": "Les paramètres et les données sont conservés dans {dir}",
      "status.stopped": "Arrêtée",

      "monitors.empty": "Aucune donnée pour l'instant. Lancez une collecte pour récupérer l'historique de chaque capteur configuré.",
      "monitors.lede": "{count} capteurs, données collectées jusqu'au {time}. Sélectionnez-en un pour tracer son évolution.",
      "monitors.unassigned": "Non affecté",
      "monitor.name": "Capteur {id}",
      "monitor.health": "santé",
      "monitor.until": "Jusqu'au {time}",
      "monitor.notYet": "Pas encore collecté",
      "monitor.samples": "{count} échantillons",
      "legend.label": "Vitesse par rapport aux seuils du capteur :",
      "zone.normal": "Normal",
      "zone.elevated": "Élevé",
      "zone.caution": "Vigilance",
      "zone.warning": "Alerte",
      "strip.none": "Aucune mesure de vitesse",
      "strip.reading": " : {value} mm/s, alerte à {warning}",
      "detail.left": "Axe gauche",
      "detail.right": "Axe droit",
      "picker.none": "Aucune",
      "chart.averaged": "{rows} échantillons, moyennés en {points} points pour l'affichage.",
      "chart.samples": "{rows} échantillons.",
      "chart.time": "Date",

      "collect.lede": "Chaque collecte récupère tout depuis le dernier échantillon collecté, plus quelques jours de chevauchement pour les données arrivées en retard.",
      "collect.idle": "Inactif",
      "collect.meter": "Progression de la collecte",
      "collect.current": "Collecte en cours",
      "collect.report": "Rapport de la dernière collecte",
      "collect.history": "Historique",
      "collect.busy": "Collecte…",
      "collect.progress": "Collecte : {done} plages horaires sur {total} récupérées",
      "collect.last": "Dernière collecte : {status}",
      "collect.noneYet": "Aucune collecte depuis l'ouverture de l'application",
      "collect.fetching": "Récupération",
      "collect.waiting": "En attente",
      "collect.chunksFailed": "{count} en échec",
      "col.monitor": "Capteur",
      "col.state": "État",
      "col.timeRanges": "Plages horaires",
      "col.note": "Remarque",
      "col.started": "Début",
      "col.result": "Résultat",
      "col.duration": "Durée",
      "col.newRows": "Nouvelles lignes",
      "col.problems": "Problèmes",
      "col.from": "Du",
      "col.to": "Au",
      "col.reason": "Motif",
      "col.actions": "Actions",
      "runs.failed": "{failed} sur {total}",
      "runs.none": "aucun",
      "runs.seconds": "{seconds} s",
      "result.SUCCESS": "Réussie",
      "result.PARTIAL": "Partielle",
      "result.FAILED": "Échouée",
      "result.INVALID_SETTINGS": "Paramètres incomplets",
      "result.COLLECTED": "Collecté",
      "result.UP_TO_DATE": "À jour",

      "data.lede": "Choisissez les capteurs, les colonnes et les lignes à exporter. Les données collectées ne sont jamais modifiées ; les filtres s'appliquent uniquement à l'aperçu et au fichier CSV.",
      "data.empty": "Aucune donnée pour l'instant. Lancez une collecte, puis revenez choisir ce que vous voulez exporter.",
      "presets.saved": "Sélections enregistrées",
      "presets.none": "Aucune enregistrée",
      "presets.load": "Charger",
      "presets.delete": "Supprimer",
      "presets.saveAs": "Enregistrer la sélection sous",
      "presets.placeholder": "ex. Pompe 2 pour MATLAB",
      "presets.save": "Enregistrer",
      "filters.monitors": "Capteurs",
      "filters.range": "Période",
      "filters.rangeHint": "UTC, les deux jours inclus",
      "filters.from": "Du",
      "filters.to": "Au",
      "filters.columns": "Colonnes",
      "filters.measurement": "Mesure",
      "filters.all": "Tout sélectionner",
      "filters.none": "Effacer",
      "filters.rows": "Lignes",
      "filters.addRule": "Ajouter une règle d'exclusion",
      "filters.sampling": "Échantillonnage",
      "sampling.raw": "Tous les échantillons",
      "sampling.hourly": "Moyennes horaires",
      "sampling.daily": "Moyennes journalières",
      "filters.dropIncomplete": "Retirer les lignes avec une valeur manquante",
      "filters.changeOnly": "Ne garder que les lignes qui ont changé",
      "filters.byMore": "de plus de",
      "filters.unit": "Unité",
      "filters.percent": "pour cent",
      "filters.absolute": "en valeur absolue",
      "filters.changeHint": "Dans n'importe quelle colonne, depuis la dernière ligne gardée. La première et la dernière ligne de chaque capteur sont toujours gardées.",
      "filters.manual": "Ignorer les plages exclues manuellement",
      "rule.lead": "Ignorer les lignes où",
      "rule.column": "Colonne",
      "rule.comparison": "Comparaison",
      "rule.gt": "est supérieur à",
      "rule.gte": "est au moins",
      "rule.lt": "est inférieur à",
      "rule.lte": "est au plus",
      "rule.value": "Valeur",
      "rule.remove": "Retirer",
      "funnel.title": "Contenu de l'export",
      "funnel.read": "Échantillons dans la période",
      "funnel.manual": "Dans les plages exclues manuellement",
      "funnel.rule": "Correspondant à la règle",
      "funnel.rules": "Correspondant à une règle",
      "funnel.hourly": "Moyennés en lignes horaires",
      "funnel.daily": "Moyennés en lignes journalières",
      "funnel.incomplete": "Avec une valeur manquante",
      "funnel.unchanged": "Inchangées depuis la dernière ligne gardée",
      "funnel.result": "Lignes à exporter, {count} colonnes chacune",
      "dataChart.title": "Vérifier le résultat sur un graphique",
      "dataChart.monitor": "Capteur",
      "dataChart.column": "Colonne",
      "dataChart.pick": "Sélectionnez un capteur et une colonne pour voir le résultat sur un graphique.",
      "dataChart.noRows": "Aucune ligne restante pour ce capteur. ",
      "dataChart.averaged": "{rows} lignes, moyennées en {points} points pour l'affichage. ",
      "dataChart.rows": "{rows} lignes. ",
      "dataChart.drag": "Faites glisser sur le graphique pour exclure une période.",
      "dataChart.dragHatched": "Faites glisser sur le graphique pour exclure une période ; les zones hachurées sont déjà exclues.",
      "exclude.reason": "Motif",
      "exclude.placeholder": "ex. Capteur remonté après maintenance",
      "exclude.submit": "Exclure ces échantillons",
      "exclude.cancel": "Annuler",
      "exclude.range": "Exclure {monitor} du {from} au {to}.",
      "exclusions.caption": "Plages exclues manuellement",
      "exclusions.include": "Réintégrer",
      "exclusions.empty": "Aucune pour les capteurs sélectionnés. Faites glisser sur le graphique pour exclure une plage, par exemple pendant l'entretien d'un capteur.",
      "export.title": "Export",
      "export.format": "Format",
      "export.comma": "Séparateur virgule, point décimal",
      "export.semicolon": "Séparateur point-virgule, virgule décimale (Excel en français, allemand, turc)",
      "export.tab": "Séparateur tabulation, point décimal",
      "export.download": "Télécharger le CSV",
      "export.preparing": "Préparation du fichier…",
      "export.done": "{name} téléchargé ({size} Ko).",
      "export.failed": "Le téléchargement a échoué. Vérifiez que l'application fonctionne toujours.",
      "preview.caption": "Aperçu",
      "preview.time": "Heure (UTC)",
      "preview.monitor": "Capteur",
      "preview.empty": "Aperçu : aucune ligne restante",
      "preview.first": "Aperçu : {shown} premières lignes sur {total}",
      "preview.all": "Aperçu : les {total} lignes",

      "settings.source": "Source des données",
      "settings.demo": "Démo",
      "settings.demoText": "Moteurs synthétiques servis par une fausse API fournisseur intégrée. Rien à configurer.",
      "settings.api": "API fournisseur",
      "settings.apiText": "Collecter depuis votre compte de surveillance vibratoire.",
      "settings.scenario": "Scénario de démo",
      "settings.motors": "Moteurs",
      "settings.days": "Jours d'historique",
      "settings.seed": "Graine aléatoire",
      "settings.baseUrl": "URL de base",
      "settings.username": "Nom d'utilisateur",
      "settings.password": "Mot de passe",
      "settings.plant": "Identifiant du site",
      "settings.monitorIds": "Identifiants des capteurs",
      "settings.firstDay": "Premier jour à collecter",
      "settings.parallel": "Requêtes parallèles",
      "settings.schedule": "Planification",
      "settings.daily": "Collecter chaque jour tant que l'application est ouverte",
      "settings.time": "Heure",
      "settings.zone": "Fuseau horaire",
      "settings.save": "Enregistrer les paramètres",
      "settings.test": "Tester la connexion",
      "settings.passwordSaved": "Enregistré. Laissez vide pour le conserver",
      "settings.fix": "Corrigez ces points avant d'enregistrer :",
      "settings.saved": "Paramètres enregistrés.",
      "settings.notSaved": "Non enregistré.",
      "settings.testing": "Test en cours…",
      "quit.text": "La collecte s'arrête quand l'application est fermée. Les données et les paramètres restent dans le dossier ci-dessus.",
      "quit.button": "Quitter l'application",
      "quit.done": "L'application est arrêtée. Vous pouvez fermer cet onglet.",

      "feature.KURTACCL": "Kurtosis",
      "feature.PPACCL": "Accélération crête à crête",
      "feature.CRESTACCL": "Facteur de crête",
      "feature.GRMSACCL": "Accélération efficace (g)",
      "feature.PPSW": "Onde de choc crête à crête",
      "feature.SWRMS": "Onde de choc efficace",
      "feature.VRMS": "Vitesse efficace (mm/s)",
      "feature.IDLE": "Part du temps à l'arrêt",
      "feature.RPM": "Vitesse de rotation (tr/min)",
      "feature.BPFI": "Amplitude BPFI (bague intérieure)",
      "feature.BPFO": "Amplitude BPFO (bague extérieure)",
      "feature.BSF": "Amplitude BSF (rotation des billes)",
      "feature.FTF": "Amplitude FTF (cage)",
      "feature.LOAD": "Charge (%)",
      "feature.TA": "Température",
    },
  };

  // Messages written by the server (validation, connection test) are English. In French they are
  // translated phrase by phrase; anything not listed here is shown as the server wrote it.
  const SERVER_FR = [
    [/^Connected to plant (\d+)( \(.*\))?: (\d+) of (\d+) monitors found(?:, missing (.*))?$/,
      (m, id, name, found, total, missing) => `Connecté au site ${id}${name || ""} : ${found} capteurs trouvés sur ${total}`
        + (missing ? `, manquants ${missing}` : "")],
    [/select at least one monitor/, "sélectionnez au moins un capteur"],
    [/a monitor is selected twice/, "un capteur est sélectionné deux fois"],
    [/select at least one column/, "sélectionnez au moins une colonne"],
    [/a column is selected twice/, "une colonne est sélectionnée deux fois"],
    [/at most 20 rules/, "20 règles au maximum"],
    [/the first day is after the last day/, "le premier jour est après le dernier jour"],
    [/the range must end after it starts/, "la plage doit finir après son début"],
    [/rule value must be a finite number/, "la valeur d'une règle doit être un nombre fini"],
    [/each rule needs a numeric value/, "chaque règle a besoin d'une valeur numérique"],
    [/change threshold must be zero or positive/, "le seuil de changement doit être positif ou nul"],
    [/percentage threshold must be at most 1000/, "le seuil en pourcentage doit être au plus 1000"],
    [/changeFilter needs a numeric threshold/, "le filtre de changement a besoin d'un seuil numérique"],
    [/a preset name needs 1 to 60 printable characters/, "le nom d'une sélection doit faire de 1 à 60 caractères imprimables"],
    [/at least one monitor id is required/, "au moins un identifiant de capteur est requis"],
    [/ is required/, " est obligatoire"],
    [/ is invalid: /, " n'est pas valide : "],
    [/must be an absolute http\(s\) URL/, "doit être une URL http(s) absolue"],
    [/must be a positive integer/, "doit être un entier positif"],
    [/must be a positive ISO-8601 duration such as PT30S/, "doit être une durée ISO-8601 positive, par exemple PT30S"],
    [/must not be negative/, "ne doit pas être négatif"],
    [/not positive integers: /, "entiers non positifs : "],
    [/duplicate ids: /, "identifiants en double : "],
    [/ must be between (\S+) and (\S+)/, " doit être compris entre $1 et $2"],
    [/ must be an integer/, " doit être un nombre entier"],
    [/ must look like 09:00/, " doit avoir la forme 09:00"],
    [/ is not a known time zone/, " n'est pas un fuseau horaire connu"],
    [/ must be a date like (\S+)/, " doit être une date comme $1"],
    [/; /, " ; "],
  ];

  let language = "en";
  const numberFormats = new Map();

  const locale = () => LOCALES[language];

  /** Translates a key, filling {placeholders} from params. */
  function t(key, params = {}) {
    const text = STRINGS[language][key] ?? STRINGS.en[key] ?? key;
    return text.replace(/\{(\w+)\}/g, (whole, name) => (name in params ? String(params[name]) : whole));
  }

  /** Translates a message written by the server, if a translation is known. */
  function server(message) {
    if (language !== "fr" || !message) return message;
    return SERVER_FR.reduce((text, [pattern, replacement]) => text.replace(pattern, replacement), message);
  }

  /** Fills every element tagged with data-i18n* attributes. */
  function translatePage(root = document) {
    root.querySelectorAll("[data-i18n]").forEach((el) => { el.textContent = t(el.dataset.i18n); });
    root.querySelectorAll("[data-i18n-placeholder]").forEach((el) => { el.placeholder = t(el.dataset.i18nPlaceholder); });
    root.querySelectorAll("[data-i18n-aria-label]").forEach((el) => { el.setAttribute("aria-label", t(el.dataset.i18nAriaLabel)); });
  }

  function setLanguage(code) {
    language = STRINGS[code] ? code : "en";
    numberFormats.clear();
    document.documentElement.lang = language;
    translatePage();
  }

  function numberFormat(digits) {
    const key = digits === undefined ? "int" : digits;
    if (!numberFormats.has(key)) {
      numberFormats.set(key, new Intl.NumberFormat(locale(), digits === undefined
        ? { maximumFractionDigits: 0 }
        : { minimumFractionDigits: digits, maximumFractionDigits: digits }));
    }
    return numberFormats.get(key);
  }

  /** A number with a fixed number of decimals, e.g. 2.80 or 2,80; "–" when missing. */
  const number = (v, digits = 2) => (v === null || v === undefined || Number.isNaN(Number(v)) ? "–" : numberFormat(digits).format(Number(v)));
  /** A whole number with grouping, e.g. 12,345 or 12 345. */
  const integer = (v) => numberFormat().format(Math.round(Number(v)));

  /** Date and time in the chosen language, local time unless utc is set. */
  const dateTime = (date, utc = false) => new Intl.DateTimeFormat(locale(),
    { dateStyle: "medium", timeStyle: "short", ...(utc ? { timeZone: "UTC" } : {}) }).format(date);

  // ── uPlot axis and legend formatters ────────────────────────────────

  const DAY = 86400;
  const fmt = (options) => new Intl.DateTimeFormat(locale(), options);

  /** Tick labels for a uPlot time axis (values in seconds, local time). */
  function timeTicks(u, splits, axisIdx, space, incr) {
    const year = fmt({ year: "numeric" });
    const month = fmt({ month: "short" });
    const monthYear = fmt({ month: "short", year: "numeric" });
    const day = fmt({ day: "numeric", month: "short" });
    const time = fmt({ hour: "numeric", minute: "2-digit" });
    let previous = null;
    return splits.map((s) => {
      const d = new Date(s * 1000);
      let label;
      if (incr >= 365 * DAY) label = year.format(d);
      else if (incr >= 28 * DAY) label = previous === null || d.getFullYear() !== previous.getFullYear() ? monthYear.format(d) : month.format(d);
      else if (incr >= DAY) label = day.format(d);
      else label = previous === null || d.getDate() !== previous.getDate() ? `${day.format(d)}\n${time.format(d)}` : time.format(d);
      previous = d;
      return label;
    });
  }

  /** Tick labels for a uPlot value axis, with as many decimals as the tick spacing needs. */
  function numberTicks(u, splits, axisIdx, space, incr) {
    const digits = Math.min(6, (String(Number((incr || 1).toFixed(10))).split(".")[1] || "").length);
    return splits.map((v) => (v === null || v === undefined ? "" : number(v, digits)));
  }

  /** The legend value of a uPlot time series. */
  const timeValue = (u, v) => (v === null || v === undefined ? "–" : dateTime(new Date(v * 1000)));

  return {
    t, server, setLanguage, translatePage, number, integer, dateTime, timeTicks, numberTicks, timeValue,
    get language() { return language; },
    languages: Object.keys(STRINGS),
  };
})();
