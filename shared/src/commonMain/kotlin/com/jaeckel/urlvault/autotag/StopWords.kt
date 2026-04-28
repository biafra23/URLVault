package com.jaeckel.urlvault.autotag

internal val ENGLISH_STOP_WORDS: Set<String> = setOf(
    "a", "about", "above", "after", "again", "against", "all", "also", "am", "an",
    "and", "any", "are", "aren", "as", "at", "be", "because", "been", "before",
    "being", "below", "between", "both", "but", "by", "can", "could", "did", "do",
    "does", "doing", "don", "down", "during", "each", "even", "few", "for", "from",
    "further", "get", "got", "had", "has", "have", "having", "he", "her", "here",
    "hers", "herself", "him", "himself", "his", "how", "if", "in", "into", "is",
    "isn", "it", "its", "itself", "just", "let", "like", "ll", "may", "me", "might",
    "more", "most", "must", "my", "myself", "no", "nor", "not", "now", "of", "off",
    "on", "once", "only", "or", "other", "our", "ours", "ourselves", "out", "over",
    "own", "re", "same", "say", "she", "should", "shouldn", "so", "some", "such",
    "than", "that", "the", "their", "theirs", "them", "themselves", "then", "there",
    "these", "they", "this", "those", "through", "to", "too", "under", "until", "up",
    "us", "use", "used", "using", "ve", "very", "was", "wasn", "we", "were", "weren",
    "what", "when", "where", "which", "while", "who", "whom", "why", "will", "with",
    "won", "would", "wouldn", "you", "your", "yours", "yourself", "yourselves",
    // Common web boilerplate words
    "click", "here", "read", "more", "home", "page", "site", "website", "web",
    "menu", "search", "sign", "log", "login", "close", "open", "next", "previous",
    "share", "follow", "skip", "content", "main", "navigation", "cookie", "cookies",
    "accept", "privacy", "policy", "terms", "conditions", "copyright", "reserved",
    "rights", "loading", "please", "wait", "error", "submit", "subscribe"
)

// Common German stopwords. List based on the standard German stopword set used by
// snowball/NLTK, plus typical web boilerplate. Note: a handful of these words also
// occur as content words in English (e.g. "die", "war", "man", "kind"). On mixed
// English/German pages they will be filtered from English text too — accepted
// tradeoff, see comment in AutoTagService.
internal val GERMAN_STOP_WORDS: Set<String> = setOf(
    // Articles
    "der", "die", "das", "den", "dem", "des",
    "ein", "eine", "einen", "einer", "eines", "einem",
    // Demonstratives / pronouns
    "dies", "diese", "dieser", "dieses", "diesem", "diesen",
    "jene", "jener", "jenes", "jenem", "jenen",
    "derselbe", "dieselbe", "dasselbe", "derselben", "denselben", "desselben", "demselben",
    "ich", "du", "er", "sie", "es", "wir", "ihr",
    "mich", "dich", "ihn", "uns", "euch", "ihnen",
    "mir", "dir", "ihm",
    "mein", "meine", "meinen", "meiner", "meines", "meinem",
    "dein", "deine", "deinen", "deiner", "deines", "deinem",
    "sein", "seine", "seinen", "seiner", "seines", "seinem",
    "ihre", "ihren", "ihrer", "ihres", "ihrem",
    "unser", "unsere", "unseren", "unserer", "unseres", "unserem",
    "euer", "eure", "euren", "eurer", "eures", "eurem",
    "selbst", "sich",
    // sein / haben / werden
    "bin", "bist", "ist", "sind", "seid", "war", "warst", "waren", "gewesen",
    "hab", "habe", "hast", "hat", "haben", "hatte", "hattest", "hatten", "gehabt",
    "werde", "wirst", "wird", "werden", "werdet", "wurde", "wurden", "geworden", "worden",
    // Modals
    "kann", "kannst", "können", "koennen", "könnt", "koennt", "konnte", "konnten",
    "könnte", "koennte", "könnten", "koennten",
    "soll", "sollst", "sollen", "sollt", "sollte", "sollten",
    "will", "willst", "wollen", "wollt", "wollte", "wollten",
    "muss", "musst", "müssen", "muessen", "müsst", "muesst", "musste", "mussten",
    "darf", "darfst", "dürfen", "duerfen", "dürft", "duerft", "durfte", "durften",
    "mag", "magst", "mögen", "moegen", "mochte", "mochten",
    // Prepositions
    "an", "am", "ans", "auf", "aus", "bei", "beim", "durch", "für", "fuer",
    "gegen", "in", "im", "ins", "mit", "nach", "ohne", "über", "ueber", "um",
    "unter", "von", "vom", "vor", "zu", "zum", "zur", "zwischen", "bis", "seit",
    "während", "waehrend", "wegen", "trotz", "statt",
    // Conjunctions / particles
    "und", "oder", "aber", "denn", "sondern", "weil", "dass", "daß", "wenn",
    "ob", "obwohl", "doch", "als", "wie", "wo", "wann", "warum", "weshalb",
    "wer", "wen", "wem", "wessen", "was", "welche", "welcher", "welches",
    "welchen", "welchem",
    // Negations / quantifiers
    "nicht", "kein", "keine", "keinen", "keiner", "keines", "keinem", "nichts",
    "alle", "allen", "aller", "alles", "allem",
    "jede", "jeder", "jedes", "jeden", "jedem",
    "manche", "mancher", "manches", "manchen", "manchem",
    "andere", "anderer", "anderes", "anderen", "anderem", "andern", "anders",
    "einige", "einiger", "einiges", "einigen", "einigem",
    "viel", "viele", "vieler", "vieles", "wenig", "wenige",
    "etwas", "mehr", "meiste",
    // Adverbs / fillers
    "auch", "nur", "noch", "schon", "sehr", "hier", "dort", "da", "damit",
    "dann", "dazu", "darum", "deshalb", "deswegen", "also", "sonst", "zwar",
    "wieder", "weiter", "weg", "hin", "hinter", "indem", "einmal", "jetzt",
    "immer", "nie", "niemals", "oft", "manchmal", "bald", "gerade", "eben",
    // German web boilerplate
    "klick", "klicken", "lesen", "startseite", "webseite", "menü", "menue",
    "suche", "suchen", "anmelden", "abmelden", "einloggen", "ausloggen",
    "schließen", "schliessen", "öffnen", "oeffnen", "zurück", "zurueck",
    "teilen", "folgen", "inhalt", "datenschutz", "richtlinie", "agb",
    "impressum", "urheberrecht", "vorbehalten", "lädt", "laedt", "laden",
    "bitte", "warten", "fehler", "abschicken", "abonnieren", "newsletter"
)

internal val STOP_WORDS: Set<String> = ENGLISH_STOP_WORDS + GERMAN_STOP_WORDS
