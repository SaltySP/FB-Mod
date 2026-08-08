package net.filebot.web;

import static java.nio.charset.StandardCharsets.*;
import static java.util.Arrays.*;
import static java.util.Collections.*;
import static java.util.stream.Collectors.*;
import static net.filebot.CachedResource.fetchIfModified;
import static net.filebot.Logging.*;
import static net.filebot.util.JsonUtilities.*;
import static net.filebot.util.StringUtilities.*;
import static net.filebot.web.EpisodeUtilities.*;
import static net.filebot.web.WebRequest.*;

import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import javax.swing.Icon;

import net.filebot.Cache;
import net.filebot.CacheType;
import net.filebot.ResourceManager;

public class TheTVDBClient extends AbstractEpisodeListProvider implements ArtworkProvider {

	private static final Locale DEFAULT_LOCALE = Locale.ENGLISH;

	private String apikey;
	private String pin;

	public TheTVDBClient(String apikey) {
		this(apikey, null);
	}

	public TheTVDBClient(String apikey, String pin) {
		this.apikey = apikey;
		this.pin = pin;
	}

	@Override
	public String getIdentifier() {
		return "TheTVDB";
	}

	@Override
	public Icon getIcon() {
		return ResourceManager.getIcon("search.thetvdb");
	}

	@Override
	public boolean hasSeasonSupport() {
		return true;
	}

	protected Object postJson(String path, Object object) throws Exception {
		// curl -X POST --header 'Content-Type: application/json' --header 'Accept: application/json' 'https://api4.thetvdb.com/v4/login' --data '{"apikey":"XXXXX","pin":"XXXXX"}'
		ByteBuffer response = post(getEndpoint(path), json(object, false).getBytes(UTF_8), "application/json", null);
		return readJson(UTF_8.decode(response));
	}

	protected Object requestJson(String path, Duration expirationTime) throws Exception {
		// TheTVDB API v4 no longer varies responses by Accept-Language header (translations are
		// fetched via dedicated per-language endpoints/paths instead), so a single shared cache
		// namespace keyed by request path is sufficient and correct.
		Cache cache = Cache.getCache(getName(), CacheType.Monthly);
		return cache.json(path, this::getEndpoint).fetch(fetchIfModified(this::getRequestHeader)).expire(expirationTime).get();
	}

	protected URL getEndpoint(String path) throws Exception {
		return new URL("https://api4.thetvdb.com/v4/" + path);
	}

	private Map<String, String> getRequestHeader() {
		Map<String, String> header = new LinkedHashMap<String, String>(2);

		header.put("Accept", "application/json");
		header.put("Authorization", "Bearer " + getAuthorizationToken());

		return header;
	}

	/**
	 * TheTVDB API v4 identifies languages by ISO 639-2/T (3-letter) codes, e.g. eng, deu, fra, zho.
	 */
	private Optional<String> getLanguageCode(Locale locale) {
		return Optional.ofNullable(locale).filter(l -> !l.getLanguage().isEmpty()).map(l -> {
			try {
				return l.getISO3Language();
			} catch (Exception e) {
				return null;
			}
		});
	}

	private String token = null;
	private Instant tokenExpireInstant = null;
	private Duration tokenExpireDuration = Duration.ofDays(27); // token expires after 1 month

	private String getAuthorizationToken() {
		synchronized (tokenExpireDuration) {
			if (token == null || (tokenExpireInstant != null && Instant.now().isAfter(tokenExpireInstant))) {
				try {
					Map<String, Object> credentials = new LinkedHashMap<String, Object>(2);
					credentials.put("apikey", apikey);
					if (pin != null && pin.length() > 0) {
						credentials.put("pin", pin);
					}

					Object json = postJson("login", credentials);
					token = getString(getMap(json, "data"), "token");
					tokenExpireInstant = Instant.now().plus(tokenExpireDuration);
				} catch (Exception e) {
					throw new IllegalStateException("Failed to retrieve authorization token: " + e.getMessage(), e);
				}
			}
			return token;
		}
	}

	@Override
	public List<SearchResult> fetchSearchResult(String query, Locale locale) throws Exception {
		Map<String, Object> parameters = new LinkedHashMap<String, Object>(2);
		parameters.put("query", query);
		parameters.put("type", "series");

		Object json = requestJson("search?" + encodeParameters(parameters, true), Cache.ONE_DAY);

		Optional<String> languageCode = getLanguageCode(locale);

		return streamJsonObjects(json, "data").map(it -> {
			// e.g. tvdb_id, name, aliases, translations, overview, image_url, year, network, status
			Integer id = getInteger(it, "tvdb_id");
			if (id == null) {
				return null;
			}

			String seriesName = getString(it, "name");

			// prefer localized name if available
			Map<?, ?> translations = getMap(it, "translations");
			if (languageCode.isPresent()) {
				String localizedName = getString(translations, languageCode.get());
				if (localizedName != null && localizedName.length() > 0) {
					seriesName = localizedName;
				}
			}

			String[] aliasNames = stream(getArray(it, "aliases")).filter(Objects::nonNull).map(Object::toString).toArray(String[]::new);

			if (seriesName == null || seriesName.startsWith("**") || seriesName.endsWith("**")) {
				debug.warning(format("Ignore invalid series: %s [%d]", seriesName, id));
				return null;
			}

			return new SearchResult(id, seriesName, aliasNames);
		}).filter(Objects::nonNull).collect(toList());
	}

	@Override
	public TheTVDBSeriesInfo getSeriesInfo(int id, Locale language) throws Exception {
		return getSeriesInfo(new SearchResult(id), language);
	}

	@Override
	public TheTVDBSeriesInfo getSeriesInfo(SearchResult series, Locale locale) throws Exception {
		Object json = requestJson("series/" + series.getId() + "/extended", Cache.ONE_WEEK);
		Object data = getMap(json, "data");

		TheTVDBSeriesInfo info = new TheTVDBSeriesInfo(this, locale, series.getId());
		info.setSlug(getString(data, "slug"));

		String[] aliasNames = Stream.concat(Stream.of(series.getAliasNames()), streamJsonObjects(data, "aliases").map(a -> getString(a, "name"))).filter(Objects::nonNull).distinct().toArray(String[]::new);
		info.setAliasNames(aliasNames);

		String name = getString(data, "name");
		String overview = getString(data, "overview");

		// try to resolve localized name/overview via /series/{id}/translations/{language}
		Optional<String> languageCode = getLanguageCode(locale);
		if (languageCode.isPresent() && !"eng".equals(languageCode.get())) {
			try {
				Object translationJson = requestJson("series/" + series.getId() + "/translations/" + languageCode.get(), Cache.ONE_WEEK);
				Object translation = getMap(translationJson, "data");

				String translatedName = getString(translation, "name");
				if (translatedName != null && translatedName.length() > 0) {
					name = translatedName;
				}
				String translatedOverview = getString(translation, "overview");
				if (translatedOverview != null && translatedOverview.length() > 0) {
					overview = translatedOverview;
				}
			} catch (Exception e) {
				debug.finest(cause("Failed to retrieve series translation", e));
			}
		}

		info.setName(name);
		info.setOverview(overview);

		info.setCertification(getCertification(data));
		info.setNetwork(getNetwork(data));
		info.setStatus(getStringOrNull(getMap(data, "status"), "name"));

		info.setRating(getDouble(data, "score"));
		info.setRatingCount(null);

		info.setRuntime(getInteger(data, "averageRuntime"));
		info.setGenres(streamJsonObjects(data, "genres").map(g -> getString(g, "name")).filter(Objects::nonNull).collect(toList()));
		info.setStartDate(getStringValue(data, "firstAired", SimpleDate::parse));

		// TheTVDB SeriesInfo extras
		info.setImdbId(streamJsonObjects(data, "remoteIds").filter(r -> "IMDB".equalsIgnoreCase(getString(r, "sourceName"))).map(r -> getString(r, "id")).findFirst().orElse(null));
		info.setAirsDayOfWeek(getAirsDayOfWeek(data));
		info.setAirsTime(getString(data, "airsTime"));
		info.setBannerUrl(getStringValue(data, "image", this::resolveImage));
		info.setLastUpdated(getStringValue(data, "lastUpdated", TheTVDBClient::parseTimestamp));

		return info;
	}

	private String getCertification(Object data) {
		List<Map<?, ?>> ratings = streamJsonObjects(data, "contentRatings").collect(toList());
		return ratings.stream().filter(r -> "usa".equalsIgnoreCase(getString(r, "country"))).map(r -> getString(r, "name")).findFirst().orElseGet(() -> {
			return ratings.stream().map(r -> getString(r, "name")).filter(Objects::nonNull).findFirst().orElse(null);
		});
	}

	private String getNetwork(Object data) {
		String network = getStringOrNull(getMap(data, "latestNetwork"), "name");
		if (network == null) {
			network = getStringOrNull(getMap(data, "originalNetwork"), "name");
		}
		return network;
	}

	private static final String[] WEEKDAYS = { "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday" };

	private String getAirsDayOfWeek(Object data) {
		Object airsDays = getMap(data, "airsDays");
		for (String day : WEEKDAYS) {
			if (Boolean.TRUE.equals(asMap(airsDays).get(day))) {
				return day.substring(0, 1).toUpperCase() + day.substring(1);
			}
		}
		return null;
	}

	private String getStringOrNull(Object node, String key) {
		return node == null ? null : getString(node, key);
	}

	private static Long parseTimestamp(String value) {
		if (value == null || value.isEmpty()) {
			return null;
		}
		try {
			return Instant.parse(value).toEpochMilli();
		} catch (Exception e) {
			// not an ISO-8601 instant, fall through
		}
		try {
			String datePart = value.length() >= 10 ? value.substring(0, 10) : value;
			return LocalDate.parse(datePart).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
		} catch (Exception e) {
			debug.warning(format("Bad lastUpdated value: %s => %s", value, e));
			return null;
		}
	}

	@Override
	protected SeriesData fetchSeriesData(SearchResult series, SortOrder sortOrder, Locale locale) throws Exception {
		// fetch series info
		SeriesInfo info = getSeriesInfo(series, locale);
		info.setOrder(sortOrder.name());

		// ignore preferred language if basic series information isn't even available
		if (info.getName() == null && !locale.equals(DEFAULT_LOCALE)) {
			return fetchSeriesData(series, sortOrder, DEFAULT_LOCALE);
		}

		// if series name isn't even available in English then just use whatever value we've got
		if (info.getName() == null) {
			info.setName(series.getName());
		}

		// DVD order needs its own season-type; all other orders (Airdate, Absolute, AbsoluteAirdate)
		// are derived from the default (aired) season-type episode list, same as TheTVDB API v2 did
		String seasonType = sortOrder == SortOrder.DVD ? "dvd" : "default";

		// fetch episode data
		List<Episode> episodes = new ArrayList<Episode>();
		List<Episode> specials = new ArrayList<Episode>();

		for (int page = 0;; page++) {
			Object json = requestJson("series/" + series.getId() + "/episodes/" + seasonType + "?page=" + page, Cache.ONE_DAY);
			Object data = getMap(json, "data");

			streamJsonObjects(data, "episodes").forEach(it -> {
				Integer id = getInteger(it, "id");
				String episodeName = getString(it, "name");

				// default to English episode title if the preferred language is not available
				if (episodeName == null && !locale.equals(DEFAULT_LOCALE)) {
					try {
						episodeName = getEpisodeList(series, sortOrder, DEFAULT_LOCALE).stream().filter(e -> id.equals(e.getId())).findFirst().map(Episode::getTitle).orElse(null);
					} catch (Exception e) {
						debug.warning(cause("Failed to retrieve default episode title", e));
					}
				}

				Integer absoluteNumber = getInteger(it, "absoluteNumber");
				SimpleDate airdate = getStringValue(it, "aired", SimpleDate::parse);

				// default numbering (aired order, or dvd order if seasonType == "dvd")
				Integer episodeNumber = getInteger(it, "number");
				Integer seasonNumber = getInteger(it, "seasonNumber");

				// adjust for forced absolute numbering (if possible)
				if (sortOrder == SortOrder.Absolute && absoluteNumber != null && absoluteNumber > 0) {
					seasonNumber = null;
					episodeNumber = absoluteNumber;
				} else if (sortOrder == SortOrder.AbsoluteAirdate && airdate != null) {
					// use airdate as absolute episode number
					seasonNumber = null;
					episodeNumber = airdate.getYear() * 1_00_00 + airdate.getMonth() * 1_00 + airdate.getDay();
				}

				if (seasonNumber == null || seasonNumber > 0) {
					// handle as normal episode
					episodes.add(new Episode(info.getName(), seasonNumber, episodeNumber, episodeName, absoluteNumber, null, airdate, id, new SeriesInfo(info)));
				} else {
					// handle as special episode
					specials.add(new Episode(info.getName(), null, null, episodeName, absoluteNumber, episodeNumber, airdate, id, new SeriesInfo(info)));
				}
			});

			Object next = asMap(getMap(json, "links")).get("next");
			if (next == null) {
				break;
			}
		}

		// episodes my not be ordered by DVD episode number
		episodes.sort(episodeComparator());

		// add specials at the end
		episodes.addAll(specials);

		return new SeriesData(info, episodes);
	}

	public SearchResult lookupByID(int id, Locale locale) throws Exception {
		if (id <= 0) {
			throw new IllegalArgumentException("Illegal TheTVDB ID: " + id);
		}

		SeriesInfo info = getSeriesInfo(new SearchResult(id), locale);
		return new SearchResult(id, info.getName(), info.getAliasNames());
	}

	public SearchResult lookupByIMDbID(int imdbid, Locale locale) throws Exception {
		if (imdbid <= 0) {
			throw new IllegalArgumentException("Illegal IMDbID ID: " + imdbid);
		}

		String remoteId = String.format("tt%07d", imdbid);
		Object json = requestJson("search/remoteid/" + remoteId, Cache.ONE_MONTH);

		return streamJsonObjects(json, "data").map(it -> getMap(it, "series")).filter(m -> m.size() > 0).map(it -> {
			Integer id = getInteger(it, "id");
			String name = getString(it, "name");
			return id == null ? null : new SearchResult(id, name);
		}).filter(Objects::nonNull).findFirst().orElse(null);
	}

	@Override
	public URI getEpisodeListLink(SearchResult searchResult) {
		return URI.create("https://www.thetvdb.com/?tab=seasonall&id=" + searchResult.getId());
	}

	@Override
	public List<Artwork> getArtwork(int id, String category, Locale locale) throws Exception {
		Map<String, Object> parameters = new LinkedHashMap<String, Object>(2);
		Integer typeId = getArtworkTypeIds().get(category == null ? null : category.toLowerCase());
		if (typeId != null) {
			parameters.put("type", typeId);
		}
		getLanguageCode(locale).ifPresent(lang -> parameters.put("lang", lang));

		String query = parameters.isEmpty() ? "" : "?" + encodeParameters(parameters, true);
		Object json = requestJson("series/" + id + "/artworks" + query, Cache.ONE_MONTH);
		Object data = getMap(json, "data");

		return streamJsonObjects(data, "artworks").map(it -> {
			URL url = getStringValue(it, "image", this::resolveImage);
			Double rating = getDouble(it, "score");
			Integer width = getInteger(it, "width");
			Integer height = getInteger(it, "height");
			boolean includesText = Boolean.TRUE.equals(asMap(it).get("includesText"));

			String resolution = (width != null && height != null) ? (width + "x" + height) : null;

			return new Artwork(Stream.of(category, includesText ? "text" : "graphical", resolution), url, locale, rating);
		}).sorted(Artwork.RATING_ORDER).collect(toList());
	}

	// cache the (mostly static) artwork type dictionary, e.g. "banner" -> 1, "poster" -> 2, "fanart" -> 3
	private Map<String, Integer> getArtworkTypeIds() throws Exception {
		Object json = requestJson("artwork/types", Cache.ONE_MONTH);

		Map<String, Integer> types = new LinkedHashMap<String, Integer>();
		streamJsonObjects(json, "data").forEach(it -> {
			String name = getString(it, "name");
			Integer typeId = getInteger(it, "id");
			if (name != null && typeId != null) {
				types.putIfAbsent(name.toLowerCase(), typeId);
			}
		});
		return types;
	}

	protected URL resolveImage(String path) {
		if (path == null || path.isEmpty()) {
			return null;
		}

		try {
			// TheTVDB API v4 returns fully qualified artwork URLs
			if (path.startsWith("http://") || path.startsWith("https://")) {
				return new URL(path);
			}
			return new URL("https://artworks.thetvdb.com/" + (path.startsWith("/") ? path.substring(1) : path));
		} catch (Exception e) {
			throw new IllegalArgumentException(path, e);
		}
	}

	public List<String> getLanguages() throws Exception {
		Object response = requestJson("languages", Cache.ONE_MONTH);
		return streamJsonObjects(response, "data").map(it -> getString(it, "shortCode")).filter(Objects::nonNull).collect(toList());
	}

	public List<Person> getActors(int seriesId, Locale locale) throws Exception {
		Object json = requestJson("series/" + seriesId + "/extended", Cache.ONE_MONTH);
		Object data = getMap(json, "data");

		return streamJsonObjects(data, "characters").filter(it -> "Actor".equalsIgnoreCase(getString(it, "peopleType"))).map(it -> {
			String name = getString(it, "personName");
			String character = getString(it, "name");
			Integer order = getInteger(it, "sort");
			URL image = getStringValue(it, "personImgURL", this::resolveImage);

			return new Person(name, character, Person.ACTOR, null, order, image);
		}).sorted(Person.CREDIT_ORDER).collect(toList());
	}

	public EpisodeInfo getEpisodeInfo(int id, Locale locale) throws Exception {
		Object response = requestJson("episodes/" + id + "/extended", Cache.ONE_MONTH);
		Object data = getMap(response, "data");

		Integer seriesId = getInteger(data, "seriesId");
		String overview = getString(data, "overview");

		Double rating = getDouble(data, "score");
		Integer votes = null; // TheTVDB API v4 no longer exposes a per-episode vote count

		List<Person> people = new ArrayList<Person>();

		streamJsonObjects(data, "characters").forEach(it -> {
			String peopleType = getString(it, "peopleType");
			String name = getString(it, "personName");

			if (name == null || peopleType == null) {
				return;
			}

			if (Person.DIRECTOR.equalsIgnoreCase(peopleType)) {
				people.add(new Person(name, Person.DIRECTOR));
			} else if (Person.WRITER.equalsIgnoreCase(peopleType)) {
				people.add(new Person(name, Person.WRITER));
			} else if (Person.GUEST_STAR.equalsIgnoreCase(peopleType)) {
				people.add(new Person(name, getString(it, "name"), Person.GUEST_STAR, null, getInteger(it, "sort"), getStringValue(it, "personImgURL", this::resolveImage)));
			}
		});

		return new EpisodeInfo(this, locale, seriesId, id, people, overview, rating, votes);
	}

}
