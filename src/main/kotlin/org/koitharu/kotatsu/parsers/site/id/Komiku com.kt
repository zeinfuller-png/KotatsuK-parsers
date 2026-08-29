package org.koitharu.kotatsu.parsers.site.id

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.PagedMangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@MangaSourceParser("KOMIKUCOM", "Komiku.com", "id")
internal class KomikuCom(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.KOMIKUCOM, pageSize = 20) {

	// The site's real content is served from a separate API host.
	private val apiDomain = "01.komiku.asia"

	override val configKeyDomain = ConfigKey.Domain("komiku.com")

	override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
		.add("Accept", "application/json, text/plain, */*")
		.add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
	)

	override val filterCapabilities = MangaListFilterCapabilities(
		isMultipleTagsSupported = true,
		isSearchSupported = true,
		isSearchWithFiltersSupported = false,
	)

	private val filterCache = ConcurrentHashMap<String, FilterResponse>()

	private class FilterResponse(
		val genres: Set<MangaTag>,
		val statuses: List<Pair<String, MangaState?>>,
		val types: List<Pair<String, ContentType?>>,
	)

	private suspend fun fetchFilterResponse(): FilterResponse {
		filterCache["all"]?.let { return it }
		val json = webClient.httpGet("https://$apiDomain/api/v2/comics/filters").parseJson()

		val genres = LinkedHashSet<MangaTag>()
		json.optJSONArray("genres")?.let { arr ->
			for (i in 0 until arr.length()) {
				val name = arr.optString(i)
				if (name.isNotBlank()) {
					genres.add(MangaTag(title = name, key = name, source = source))
				}
			}
		}

		val statuses = ArrayList<Pair<String, MangaState?>>()
		json.optJSONArray("statuses")?.let { arr ->
			for (i in 0 until arr.length()) {
				val value = arr.optString(i)
				if (value.isBlank() || value.equals("Semua", ignoreCase = true)) continue
				statuses.add(value to parseState(value))
			}
		}

		val types = ArrayList<Pair<String, ContentType?>>()
		json.optJSONArray("types")?.let { arr ->
			for (i in 0 until arr.length()) {
				val value = arr.optString(i)
				if (value.isBlank() || value.equals("Semua", ignoreCase = true)) continue
				types.add(value to parseContentType(value))
			}
		}

		return FilterResponse(genres, statuses, types).also { filterCache["all"] = it }
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val filters = fetchFilterResponse()
		return MangaListFilterOptions(
			availableTags = filters.genres,
			availableStates = filters.statuses.mapNotNullTo(EnumSet.noneOf(MangaState::class.java)) { it.second },
			availableContentTypes = filters.types.mapNotNullTo(EnumSet.noneOf(ContentType::class.java)) { it.second },
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim().orEmpty()

		if (query.isNotEmpty()) {
			// The search endpoint requires `q` and returns a bare JSON array, not a paged object.
			val url = buildString {
				append("https://").append(apiDomain).append("/api/v2/comics/search")
				append("?q=").append(query.urlEncoded())
				append("&page=").append(page)
			}
			val array = webClient.httpGet(url).parseJsonArray()
			return parseComicList(array)
		}

		val sortParam = when (order) {
			SortOrder.POPULARITY -> "popular"
			SortOrder.UPDATED -> "update"
			else -> "update"
		}
		val url = buildString {
			append("https://").append(apiDomain).append("/api/v2/comics")
			append("?sort=").append(sortParam)
			append("&page=").append(page)
			filter.tags.forEach { tag ->
				append("&genres=").append(tag.key.urlEncoded())
			}
			filter.states.firstOrNull()?.let {
				append("&status=").append(it.toApiValue().urlEncoded())
			}
			filter.types.firstOrNull()?.let {
				append("&type=").append(it.toApiValue().urlEncoded())
			}
		}
		val json = webClient.httpGet(url).parseJson()
		return parseComicList(json.optJSONArray("items"))
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val slug = manga.url.substringAfter("/manga/")
		val detailsDeferred = async { webClient.httpGet("https://$apiDomain/api/v2/comics/$slug").parseJson() }
		val data = detailsDeferred.await()
		val comicId = data.optInt("id", 0)
		val chapters = if (comicId != 0) {
			webClient.httpGet("https://$apiDomain/api/v2/comics/$comicId/chapters").parseJson()
		} else {
			null
		}
		manga.copy(
			title = data.optString("title").ifBlank { manga.title },
			coverUrl = data.optString("coverUrl").ifEmpty { manga.coverUrl },
			author = parseAuthor(data),
			description = data.optString("synopsis").ifEmpty { null },
			tags = parseTags(data.optJSONArray("genres")),
			state = parseState(data.optString("comicStatus")),
			chapters = parseChapters(chapters, comicId),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val parts = chapter.url.trim('/').split('/')
		val comicId = parts.getOrNull(0) ?: return emptyList()
		val chapterId = parts.getOrNull(1) ?: return emptyList()
		val json = webClient.httpGet("https://$apiDomain/api/v2/comics/$comicId/chapters/id/$chapterId").parseJson()
		val pages = json.optJSONArray("pages") ?: return emptyList()
		return List(pages.length()) { i -> pages.optJSONObject(i)?.optString("url").orEmpty() }
			.filter { it.isNotEmpty() }
			.mapIndexed { index, imageUrl ->
				MangaPage(
					id = generateUid("$comicId-$chapterId-$index"),
					url = imageUrl,
					preview = null,
					source = source,
				)
			}
	}

	private fun parseComicList(arr: JSONArray?): List<Manga> {
		if (arr == null) return emptyList()
		val result = ArrayList<Manga>(arr.length())
		for (i in 0 until arr.length()) {
			val jo = arr.optJSONObject(i) ?: continue
			val slug = jo.optString("slug")
			if (slug.isBlank()) continue
			val href = "/manga/$slug"
			result.add(
				Manga(
					id = generateUid(href),
					title = jo.optString("title").ifBlank { "Untitled" },
					altTitle = null,
					url = href,
					publicUrl = href.toAbsoluteUrl(domain),
					rating = RATING_UNKNOWN,
					isNsfw = isNsfwSource,
					coverUrl = jo.optString("coverUrl"),
					tags = parseTags(jo.optJSONArray("genres")),
					state = parseState(jo.optString("comicStatus")),
					author = parseAuthor(jo),
					largeCoverUrl = null,
					description = null,
					source = source,
				),
			)
		}
		return result
	}

	private fun parseChapters(json: JSONObject?, comicId: Int): List<MangaChapter> {
		val rawArray = json?.optJSONArray("chapters") ?: return emptyList()
		val list = ArrayList<MangaChapter>(rawArray.length())
		for (i in 0 until rawArray.length()) {
			val jo = rawArray.optJSONObject(i) ?: continue
			val id = jo.optInt("id", -1)
			if (id < 0) continue
			val number = jo.optDouble("n", 0.0).toFloat()
			val chapterTitle = jo.optString("title").ifBlank {
				"Chapter ${formatChapterNumber(number)}"
			}
			list.add(
				MangaChapter(
					id = generateUid("$comicId-$id"),
					name = chapterTitle,
					number = number,
					volume = 0,
					url = "$comicId/$id",
					scanlator = null,
					uploadDate = parseRelativeLabel(jo.optString("releasedLabel")),
					branch = null,
					source = source,
				),
			)
		}
		return list.sortedWith(compareBy<MangaChapter> { it.number }.thenBy { it.uploadDate })
	}

	private fun formatChapterNumber(number: Float): String =
		if (number % 1f == 0f) number.toInt().toString() else number.toString()

	private fun parseAuthor(jo: JSONObject): String? {
		val author = jo.optString("author").trim()
		val artist = jo.optString("artist").trim()
		return listOf(author, artist).filter { it.isNotBlank() && it != "null" }
			.distinct()
			.joinToString()
			.ifBlank { null }
	}

	private fun parseTags(arr: JSONArray?): Set<MangaTag> {
		if (arr == null) return emptySet()
		val tags = LinkedHashSet<MangaTag>(arr.length())
		for (i in 0 until arr.length()) {
			val name = arr.optString(i)
			if (name.isNotBlank()) {
				tags.add(MangaTag(title = name, key = name, source = source))
			}
		}
		return tags
	}

	private fun parseState(status: String): MangaState? = when (status.trim().lowercase()) {
		"ongoing" -> MangaState.ONGOING
		"completed" -> MangaState.FINISHED
		"hiatus" -> MangaState.PAUSED
		"cancelled", "canceled" -> MangaState.ABANDONED
		else -> null
	}

	private fun MangaState.toApiValue(): String = when (this) {
		MangaState.ONGOING -> "ongoing"
		MangaState.FINISHED -> "completed"
		MangaState.PAUSED -> "hiatus"
		MangaState.ABANDONED -> "cancelled"
		else -> ""
	}

	private fun parseContentType(value: String): ContentType? = when (value.trim().lowercase()) {
		"manga" -> ContentType.MANGA
		"manhwa" -> ContentType.MANHWA
		"manhua" -> ContentType.MANHUA
		else -> null
	}

	private fun ContentType.toApiValue(): String = when (this) {
		ContentType.MANGA -> "manga"
		ContentType.MANHWA -> "manhwa"
		ContentType.MANHUA -> "manhua"
		else -> ""
	}

	// releasedLabel example: "5 menit", "2 jam", "3 hari", "9 Feb 2022", "baru saja"
	private fun parseRelativeLabel(label: String?): Long {
		if (label.isNullOrBlank()) return 0L
		val now = System.currentTimeMillis()
		when {
			"baru saja" in label -> return now
			"menit" in label -> return now - (label.filter { it.isDigit() }.toLongOrNull() ?: 0L) * 60_000L
			"jam" in label -> return now - (label.filter { it.isDigit() }.toLongOrNull() ?: 0L) * 3_600_000L
			"hari" in label -> return now - (label.filter { it.isDigit() }.toLongOrNull() ?: 0L) * 86_400_000L
		}
		val parts = label.split(" ")
		if (parts.size == 3) {
			val day = parts[0].toIntOrNull() ?: return 0L
			val month = MONTH_ABBREVIATIONS[parts[1].lowercase()] ?: return 0L
			val year = parts[2].toIntOrNull() ?: return 0L
			return runCatching {
				LocalDate.of(year, month, day)
					.atStartOfDay(ZoneId.systemDefault())
					.toInstant()
					.toEpochMilli()
			}.getOrDefault(0L)
		}
		return 0L
	}

	private companion object {
		val MONTH_ABBREVIATIONS = mapOf(
			"jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "mei" to 5, "may" to 5,
			"jun" to 6, "jul" to 7, "agu" to 8, "aug" to 8, "sep" to 9, "okt" to 10,
			"oct" to 10, "nov" to 11, "des" to 12, "dec" to 12,
		)
	}
}
