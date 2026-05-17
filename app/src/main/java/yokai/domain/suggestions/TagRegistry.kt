package yokai.domain.suggestions

object TagRegistry {
    val patterns: List<TagPattern> = listOf(
        tag("action", "Action", exact = setOf("action swordplay", "sword fighting")),
        tag("adventure", "Adventure"),
        tag("comedy", "Comedy", exact = setOf("humor", "humour")),
        tag("drama", "Drama"),
        tag(
            "fantasy",
            "Fantasy",
            exact = setOf("high fantasy", "dark fantasy"),
            regexes = listOf("""^high.fantasy$""", """^dark.fantasy$"""),
        ),
        tag("horror", "Horror"),
        tag("mystery", "Mystery", exact = setOf("detective")),
        tag(
            "psychological",
            "Psychological",
            exact = setOf("psycho", "psych"),
            prefixes = setOf("psycho"),
        ),
        tag(
            "romance",
            "Romance",
            exact = setOf(
                "romcom",
                "rom-com",
                "romantic comedy",
                "love story",
                "love stories",
                "ngon tinh",
                "ngôn tình",
            ),
            prefixes = setOf("romance"),
        ),
        tag("science fiction", "Science Fiction", exact = setOf("sci fi", "sci-fi", "scifi", "sf")),
        tag("slice of life", "Slice of Life", exact = setOf("slice-of-life", "daily life")),
        tag("sports", "Sports"),
        tag("supernatural", "Supernatural", exact = setOf("super natural", "super-natural")),
        tag("thriller", "Thriller"),
        tag("tragedy", "Tragedy"),
        tag("historical", "Historical"),

        tag("martial arts", "Martial Arts", exact = setOf("martial-arts", "martialarts", "kung fu")),
        tag("mecha", "Mecha"),
        tag("medical", "Medical"),
        tag("philosophical", "Philosophical"),
        tag("superhero", "Superhero", exact = setOf("super hero", "super-power", "super power")),
        tag("wuxia", "Wuxia"),
        tag("xianxia", "Xianxia"),
        tag("xuanhuan", "Xuanhuan"),
        tag("crime", "Crime", exact = setOf("mafia")),
        tag(
            "magical girls",
            "Magical Girls",
            exact = setOf("magical-girls"),
            prefixes = setOf("magical girl"),
        ),
        tag("music", "Music"),
        tag("cooking", "Cooking"),
        tag("game", "Game", exact = setOf("video game", "video games", "gaming")),

        tag("shonen", "Shonen", exact = setOf("shounen"), prefixes = setOf("shonen", "shounen")),
        tag("shojo", "Shojo", exact = setOf("shoujo"), prefixes = setOf("shojo", "shoujo")),
        tag("seinen", "Seinen", prefixes = setOf("seinen")),
        tag("josei", "Josei", prefixes = setOf("josei")),

        tag(
            "boys love",
            "Boys Love",
            exact = setOf(
                "bl",
                "yaoi",
                "boys' love",
                "shounen ai",
                "shonen ai",
                "shounen-ai",
                "shonen-ai",
                "dam my",
                "đam mỹ",
            ),
            regexes = listOf("""^(bo(y|ys).?love)$""", """^(sho?u?nen.?ai)$"""),
        ),
        tag(
            "girls love",
            "Girls Love",
            exact = setOf(
                "gl",
                "yuri",
                "girls' love",
                "shoujo ai",
                "shojo ai",
                "shoujo-ai",
                "shojo-ai",
                "shoujoai",
                "shojoai",
                "bach hop",
                "bách hợp",
            ),
            regexes = listOf("""^(girls?.?love)$""", """^(sho?u?jo.?ai)$"""),
        ),
        tag("harem", "Harem", exact = setOf("harem male protagonist"), prefixes = setOf("harem")),
        tag("reverse harem", "Reverse Harem", exact = setOf("reverse-harem")),
        tag("netorare", "Netorare", exact = setOf("ntr", "netori", "netorase", "cheating")),
        tag("incest", "Incest", exact = setOf("inseki", "thông dâm", "loạn luân")),
        tag("vanilla", "Vanilla", exact = setOf("wholesome")),
        tag(
            "futanari",
            "Futanari",
            exact = setOf("futa", "shemale", "dickgirl", "dickgirls"),
            prefixes = setOf("futa"),
            regexes = listOf("""^(dickgirl|dickgirls)$"""),
        ),
        tag(
            "crossdressing",
            "Crossdressing",
            exact = setOf(
                "cross-dressing",
                "cross dressing",
                "gender bender",
                "gender-bender",
                "genderswap",
                "gender-swap",
                "gender swap",
                "bodyswap",
                "body swap",
                "body-swap",
                "trap",
                "tomgirl",
                "feminization",
            ),
            regexes = listOf(
                """^(gender.?bender)$""",
                """^(gender.?swap)$""",
                """^(body.?swap)$""",
            ),
        ),

        tag(
            "isekai",
            "Isekai",
            exact = setOf(
                "reincarnation",
                "tensei",
                "transferred to another world",
                "transported to another world",
                "summoned to another world",
            ),
            prefixes = setOf("isekai"),
            regexes = listOf(
                """^(re.?incarnation)$""",
                """^(transferred|transported|summoned).*another.*world""",
            ),
        ),
        tag("reverse isekai", "Reverse Isekai", exact = setOf("reverse-isekai")),
        tag("villainess", "Villainess", exact = setOf("villain"), regexes = listOf("""^villain""")),
        tag("regression", "Regression", exact = setOf("regressor", "returner"), prefixes = setOf("regress")),
        tag("transmigration", "Transmigration", exact = setOf("transmigrator"), prefixes = setOf("transmigrat")),
        tag("tower climbing", "Tower Climbing", exact = setOf("tower-climbing", "tower")),
        tag("dungeon", "Dungeon", exact = setOf("dungeons")),

        tag(
            "mother",
            "Mother",
            exact = setOf(
                "mom",
                "stepmom",
                "step-mom",
                "step mom",
                "stepmother",
                "step-mother",
                "step mother",
            ),
            prefixes = setOf("mother", "mom"),
            regexes = listOf("""^step.?m(o|othe)r$"""),
        ),
        tag(
            "milf",
            "MILF",
            exact = setOf("milfs", "milves", "m.i.l.f", "m.i.l.f."),
            regexes = listOf("""^m\.?i\.?l\.?f\.?$"""),
        ),
        tag(
            "office",
            "Office",
            exact = setOf(
                "office romance",
                "office lady",
                "office ladies",
                "salaryman",
                "salarymen",
                "office workers",
                "workplace",
            ),
            prefixes = setOf("office"),
        ),
        tag(
            "school life",
            "School Life",
            exact = setOf("school-life", "high school", "academy", "campus", "college life"),
            prefixes = setOf("school"),
            regexes = listOf("""^(high.?school)$""", """^(college.?life)$"""),
        ),
        tag(
            "demon",
            "Demon",
            exact = setOf("demons", "oni"),
            prefixes = setOf("demon"),
            regexes = listOf("""^oni$"""),
        ),
        tag(
            "monster girls",
            "Monster Girls",
            exact = setOf(
                "monster-girls",
                "monster girl",
                "monster-girl",
                "kemonomimi",
                "catgirl",
                "fox girl",
                "dog girl",
                "wolf girl",
                "slime girl",
                "snake girl",
                "lizard girl",
                "spider girl",
                "centaur",
                "harpy",
                "mermaid",
            ),
            prefixes = setOf("monster girl"),
            regexes = listOf(
                """^(kemonomimi)$""",
                """^(catgirl|cat.girl)$""",
                """^(fox.girl)$""",
            ),
        ),
        tag("monster", "Monster", exact = setOf("monsters", "beasts"), prefixes = setOf("monster", "beast")),
        tag("vampire", "Vampire", exact = setOf("vampires"), prefixes = setOf("vampire")),
        tag("zombie", "Zombie", exact = setOf("zombies"), prefixes = setOf("zombie")),

        tag("aliens", "Aliens", exact = setOf("alien"), prefixes = setOf("alien")),
        tag("ghosts", "Ghosts", exact = setOf("ghost"), prefixes = setOf("ghost")),
        tag(
            "post-apocalyptic",
            "Post-apocalyptic",
            exact = setOf("post apocalyptic", "postapocalyptic", "apocalypse"),
            prefixes = setOf("post-apocalyptic", "post apocalyptic", "postapocalyptic"),
            regexes = listOf("""^apocalypse$"""),
        ),
        tag("military", "Military", exact = setOf("war", "soldier"), prefixes = setOf("military", "soldier")),
        tag("survival", "Survival"),
        tag("time travel", "Time Travel", exact = setOf("time-travel")),
        tag("virtual reality", "Virtual Reality", exact = setOf("virtual-reality", "vr", "virtual world", "full dive")),
        tag("ninja", "Ninja", prefixes = setOf("ninja")),
        tag("samurai", "Samurai", prefixes = setOf("samurai")),
        tag(
            "delinquents",
            "Delinquents",
            exact = setOf("delinquent", "bad boy", "yankee", "bullying"),
            prefixes = setOf("delinquent"),
        ),
        tag("gyaru", "Gyaru", exact = setOf("gyaru-oh"), prefixes = setOf("gyaru")),
        tag("magic", "Magic", prefixes = setOf("magic")),
        tag(
            "cultivation",
            "Cultivation",
            exact = setOf("cultivate", "qi", "martial peak"),
            prefixes = setOf("cultivat"),
        ),
        tag(
            "royalty",
            "Royalty",
            exact = setOf("royal family", "royal", "noble", "emperor", "prince", "princess", "duke", "duchess"),
            prefixes = setOf("royal", "noble", "emperor", "prince", "princess"),
        ),
        tag("space", "Space", prefixes = setOf("space")),
        tag(
            "showbiz",
            "Showbiz",
            exact = setOf("show biz", "show-biz", "entertainment", "idol", "celebrity", "actor", "actress"),
            prefixes = setOf("showbiz"),
        ),

        tag("ecchi", "Ecchi", prefixes = setOf("ecchi")),
        tag(
            "mature",
            "Mature",
            exact = setOf("adult", "18+", "18 plus", "r18", "r-18", "mature woman", "mature female"),
            prefixes = setOf("mature"),
            regexes = listOf("""^(adult)$""", """^(18.?\+|18.?plus)$""", """^r.?18$"""),
        ),
        tag("smut", "Smut", prefixes = setOf("smut")),
        tag("hentai", "Hentai", prefixes = setOf("hentai")),
        tag(
            "gore",
            "Gore",
            exact = setOf("bloody", "violence", "blood", "guro"),
            regexes = listOf("""^(guro)$"""),
        ),
        tag(
            "sexual violence",
            "Sexual Violence",
            exact = setOf(
                "rape",
                "non-con",
                "noncon",
                "dub-con",
                "dubcon",
                "mind break",
                "mind-break",
                "corruption",
                "hiếp dâm",
                "cưỡng hiếp",
                "hãm hiếp",
            ),
            regexes = listOf(
                """^(rape)$""",
                """^(non.?con)$""",
                """^(dub.?con)$""",
                """^(mind.?break)$""",
                """^(mind.?control)$""",
            ),
        ),
        tag("loli", "Loli", exact = setOf("lolicon", "lolicons", "lolita"), prefixes = setOf("loli")),
        tag("shota", "Shota", exact = setOf("shotacon", "shota con"), prefixes = setOf("shota")),
        tag("ahegao", "Ahegao", exact = setOf("ahegao face"), prefixes = setOf("ahegao")),
        tag(
            "bdsm",
            "BDSM",
            exact = setOf(
                "bondage",
                "domination",
                "submission",
                "slave",
                "petplay",
                "shibari",
                "spanking",
                "humiliation",
                "degradation",
                "femdom",
                "maledom",
                "chastity",
            ),
            regexes = listOf("""^(bdsm)$""", """^(femdom|male.?dom)$"""),
        ),

        tag(
            "doujinshi",
            "Doujinshi",
            exact = setOf("doujin", "dojinshi", "doujinshi original series"),
            prefixes = setOf("doujin"),
        ),
        tag(
            "oneshot",
            "Oneshot",
            exact = setOf("one-shot", "one shot", "oneshots"),
            regexes = listOf("""^(one.?shot)"""),
        ),
        tag("4 koma", "4 Koma", exact = setOf("4-koma", "4koma"), regexes = listOf("""^4.?koma$""")),
        tag(
            "full color",
            "Full Color",
            exact = setOf("full-color", "fullcolor", "colored", "full coloured", "digital coloring", "truyện màu"),
            regexes = listOf("""^(full.?colo(u)?r)$""", """^(digitally.?colo(u)?red)$"""),
        ),
        tag(
            "long strip",
            "Long Strip",
            exact = setOf("long-strip", "longstrip"),
            regexes = listOf("""^long.?strip$"""),
        ),
        tag(
            "web comic",
            "Web Comic",
            exact = setOf("web-comic", "webcomic", "webtoon"),
            regexes = listOf("""^(web.?com(ic)?)$""", """^(webtoon)$"""),
        ),
        tag("adaptation", "Adaptation"),
        tag("anthology", "Anthology"),
        tag(
            "award winning",
            "Award Winning",
            exact = setOf("award-winning", "awardwinning", "contest winning"),
            regexes = listOf("""^(award.?winning)$""", """^(contest.?winning)$"""),
        ),
        tag(
            "fan colored",
            "Fan Colored",
            exact = setOf("fan-colored", "fancolored", "fan coloured"),
            regexes = listOf("""^fan.?colo(u)?red$"""),
        ),
        tag(
            "official colored",
            "Official Colored",
            exact = setOf("official-colored", "officialcolored", "official coloured"),
            regexes = listOf("""^official.?colo(u)?red$"""),
        ),
        tag(
            "self-published",
            "Self-published",
            exact = setOf("self published", "selfpublished", "user created"),
            regexes = listOf("""^(self.?publish(ed)?)$""", """^(user.?created)$"""),
        ),
        tag(
            "imageset",
            "Imageset",
            exact = setOf("image set", "image-set", "artist cg", "cg", "artbook"),
            regexes = listOf("""^(image.?set)$""", """^(artist.?cg)$""", """^(artbook)$"""),
        ),

        tag("manga", "Manga"),
        tag("manhua", "Manhua", prefixes = setOf("manhua")),
        tag("manhwa", "Manhwa", prefixes = setOf("manhwa")),
        tag("western", "Western", exact = setOf("comic", "cartoon", "comics", "american")),

        tag("tentacles", "Tentacles", exact = setOf("tentacle"), prefixes = setOf("tentacle")),
        tag(
            "pregnancy",
            "Pregnancy",
            exact = setOf("pregnant", "impregnation", "impregnate", "breeding", "breed"),
            prefixes = setOf("pregnant", "impregnat"),
            regexes = listOf("""^(breeding|breed)$"""),
        ),
        tag(
            "lactation",
            "Lactation",
            exact = setOf("breast feeding", "breastfeeding", "milk", "lactating", "hucow"),
            prefixes = setOf("lactat"),
            regexes = listOf("""^(breast.?feed(ing)?)$""", """^(hucow)$"""),
        ),
        tag(
            "exhibitionism",
            "Exhibitionism",
            exact = setOf(
                "public sex",
                "public nudity",
                "flashing",
                "indecent exposure",
                "voyeur",
                "voyeurism",
                "hidden camera",
                "filming",
            ),
            regexes = listOf(
                """^(public.?sex)$""",
                """^(indecent.?exposure)$""",
                """^(hidden.?camera)$""",
            ),
        ),
        tag(
            "prostitution",
            "Prostitution",
            exact = setOf("prostitute", "escort", "hooker", "sex work", "paid sex", "whore"),
        ),
        tag("blackmail", "Blackmail", exact = setOf("extortion", "sextortion", "coercion")),
        tag(
            "drugs",
            "Drugs",
            exact = setOf("aphrodisiac", "aphrodisiacs", "sex drugs", "drugged", "date rape drug"),
            regexes = listOf("""^(aphrodisiac)"""),
        ),
        tag("cosplay", "Cosplay", exact = setOf("cosplaying", "cosplayer"), prefixes = setOf("cosplay")),
        tag(
            "age gap",
            "Age Gap",
            exact = setOf(
                "age-gap",
                "agegap",
                "older man",
                "younger woman",
                "older woman",
                "younger man",
                "age regression",
                "age progression",
            ),
            regexes = listOf(
                """^(age.?gap)$""",
                """^(older|younger).*(man|woman|male|female)$""",
                """^(age.?regression|age.?progression)$""",
            ),
        ),

        tag("assassins", "Assassins", exact = setOf("assassin", "hitman"), prefixes = setOf("assassin")),
        tag(
            "childhood friends",
            "Childhood Friends",
            exact = setOf("childhood friend", "childhood love", "friends to lovers", "osananajimi"),
            regexes = listOf("""^(childhood.?friend)"""),
        ),
        tag("sole female", "Sole Female", exact = setOf("1girl", "female solo")),
        tag("sole male", "Sole Male", exact = setOf("1boy", "male solo")),
        tag("big breasts", "Big Breasts", exact = setOf("large breasts", "huge breasts", "oppai")),
        tag("nakadashi", "Nakadashi", exact = setOf("creampie")),
        tag("blowjob", "Blowjob", exact = setOf("fellatio", "oral sex")),
        tag("anal", "Anal", exact = setOf("anal sex")),
        tag("muscular", "Muscular", exact = setOf("big guy", "big muscles", "muscular male")),
    )

    private val exactLookup: Map<String, TagPattern> = buildMap {
        patterns.forEach { pattern ->
            pattern.exact.forEach { rawKey ->
                putIfAbsent(rawKey, pattern)
            }
        }
    }

    private val prefixLookup: List<Pair<String, TagPattern>> =
        patterns.flatMap { pattern -> pattern.prefixes.map { prefix -> prefix to pattern } }

    private val regexLookup: List<Pair<Regex, TagPattern>> =
        patterns.flatMap { pattern -> pattern.patterns.map { regex -> regex to pattern } }

    val defaultAliases: List<Pair<String, String>> =
        exactLookup.map { (rawKey, pattern) -> rawKey to pattern.canonicalKey }

    private val canonicalKeys: Set<String> =
        patterns.mapTo(linkedSetOf()) { it.canonicalKey }

    fun find(rawKey: String): TagPattern? {
        exactLookup[rawKey]?.let { return it }
        prefixLookup.firstOrNull { (prefix, _) -> rawKey.startsWith(prefix) }?.second?.let { return it }
        return regexLookup.firstOrNull { (regex, _) -> regex.containsMatchIn(rawKey) }?.second
    }

    fun isKnown(rawKey: String): Boolean =
        rawKey in canonicalKeys || rawKey in exactLookup

    private fun tag(
        canonicalKey: String,
        displayName: String,
        exact: Set<String> = emptySet(),
        prefixes: Set<String> = emptySet(),
        regexes: List<String> = emptyList(),
    ): TagPattern =
        TagPattern(
            canonicalKey = canonicalKey,
            displayName = displayName,
            exact = (exact + canonicalKey).toCollection(linkedSetOf()),
            prefixes = prefixes,
            patterns = regexes.map { Regex(it) },
        )
}
