package eu.kanade.tachiyomi.animeextension.pt.tomatobr;

import android.app.Application;
import android.content.SharedPreferences;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.Toast;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceScreen;
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource;
import eu.kanade.tachiyomi.animesource.model.*;
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource;
import okhttp3.*;
import org.json.*;
import rx.Observable;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Account-configured Tomato adapter. Host libraries are compile-only. */
public final class TomatoBR extends AnimeHttpSource implements ConfigurableAnimeSource {
    public static final String API = "https://prod-api.tomatoanimes.com";
    private static final String TOKEN_KEY = "tomato_account_token";
    private static final String LANGUAGE_KEY = "tomato_language";
    private static final String QUALITY_KEY = "tomato_quality";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final Map<Integer, SAnime> cardCache = new ConcurrentHashMap<>();
    private volatile SharedPreferences preferences;
    private volatile Context appContext;
    private long lastMissingNotice;
    private long previousRequest;
    private static final String ASSOCIATION_RESET = "tomato_associations_reset_v14_4";

    public TomatoBR() {
        // The host may load a saved association without calling search. Migrate at load time.
        try { getSourcePreferences(); } catch (RuntimeException ignored) {
            // Hosts without an initialized Application retry on first authenticated request.
        }
    }

    @Override public String getName() { return "Tomato BR"; }
    @Override public String getLang() { return "pt-BR"; }
    @Override public String getBaseUrl() { return API; }
    @Override public boolean getSupportsLatest() { return true; }

    @Override public SharedPreferences getSourcePreferences() {
        if (preferences == null) {
            synchronized (this) {
                if (preferences == null) {
                    try {
                        // The host's public Injekt API supplies its Application; no hidden Android API.
                        ClassLoader loader = AnimeHttpSource.class.getClassLoader();
                        Object scope = Class.forName("uy.kohesive.injekt.InjektKt", true, loader)
                            .getMethod("getInjekt").invoke(null);
                        Class<?> factory = Class.forName("uy.kohesive.injekt.api.InjektFactory", true, loader);
                        Application app = (Application) factory.getMethod("getInstance", Type.class)
                            .invoke(scope, Application.class);
                        appContext = app;
                        preferences = app.getSharedPreferences("source_" + getId(), 0);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException("Abra as configurações da fonte Tomato BR para configurar sua sessão.", e);
                    }
                }
            }
        }
        migrateLegacyAssociations();
        return preferences;
    }

    private synchronized void migrateLegacyAssociations() {
        Context context = appContext;
        if (preferences == null || context == null || !"ani.dantotsu".equals(context.getPackageName())) return;
        if (preferences.getBoolean(ASSOCIATION_RESET, false)) return;
        SharedPreferences cache = context.getSharedPreferences("ani.dantotsu.irrelevant", 0);
        SharedPreferences backup = context.getSharedPreferences("tomato_br_association_backup_v14_4", 0);
        SharedPreferences.Editor save = backup.edit();
        SharedPreferences.Editor clear = cache.edit();
        int count = 0;
        for (Map.Entry<String, ?> entry : cache.getAll().entrySet()) {
            // Only this source's saved ShowResponse records; keep Selected-* settings.
            if (!entry.getKey().matches("Tomato BR_[0-9]+") || !(entry.getValue() instanceof String)) continue;
            save.putString(entry.getKey(), (String) entry.getValue());
            clear.remove(entry.getKey());
            count++;
        }
        // Never invalidate without a completed private backup. Retrying is safe.
        if (count > 0 && !save.commit()) return;
        if (count > 0 && !clear.commit()) return;
        preferences.edit().putBoolean(ASSOCIATION_RESET, true).commit();
    }

    @Override public void setupPreferenceScreen(PreferenceScreen screen) {
        appContext = screen.getContext().getApplicationContext();
        preferences = screen.getContext().getApplicationContext().getSharedPreferences("source_" + getId(), 0);
        EditTextPreference session = new EditTextPreference(screen.getContext());
        session.setKey(TOKEN_KEY);
        session.setTitle("Token da sua conta Tomato");
        session.setSummary("Sessão pessoal necessária. Salva apenas neste aplicativo; não é incluída no APK.");
        session.setText(getSourcePreferences().getString(TOKEN_KEY, ""));
        session.setOnBindEditTextListener(edit -> edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
        session.setOnPreferenceChangeListener((preference, value) -> {
            String token = normalizeToken(String.valueOf(value));
            getSourcePreferences().edit().putString(TOKEN_KEY, token).apply();
            cardCache.clear();
            return true;
        });
        screen.addPreference(session);
        addChoice(screen, LANGUAGE_KEY, "Episódios", new String[]{"Todos", "Legendado", "Dublado"}, "Todos");
        addChoice(screen, QUALITY_KEY, "Qualidade preferida", new String[]{"1080p", "720p", "480p"}, "1080p");
    }

    private void addChoice(PreferenceScreen screen, String key, String title, String[] choices, String fallback) {
        ListPreference pref = new ListPreference(screen.getContext());
        pref.setKey(key);
        pref.setTitle(title);
        pref.setEntries(choices);
        pref.setEntryValues(choices);
        pref.setDefaultValue(fallback);
        pref.setSummary("%s");
        pref.setOnPreferenceChangeListener((unused, value) -> {
            getSourcePreferences().edit().putString(key, String.valueOf(value)).apply();
            return true;
        });
        screen.addPreference(pref);
    }

    public static String normalizeToken(String raw) {
        String token = raw == null ? "" : raw.trim();
        if (token.regionMatches(true, 0, "Bearer ", 0, 7)) token = token.substring(7).trim();
        if (token.contains("\r") || token.contains("\n")) throw new IllegalArgumentException("Token inválido: contém quebra de linha.");
        return token;
    }

    private String token() {
        String value = normalizeToken(getSourcePreferences().getString(TOKEN_KEY, ""));
        if (value.isEmpty()) throw new IllegalStateException("Configure o token da sua conta em Tomato BR > Configurações.");
        return value;
    }

    @Override protected Headers.Builder headersBuilder() {
        return new Headers.Builder().set("User-Agent", "tomato-android").set("Accept", "application/json");
    }

    private Request request(String path, JSONObject body) {
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("://")) throw new IllegalArgumentException("Rota Tomato inválida.");
        Request.Builder builder = new Request.Builder().url(API + path)
            .headers(headersBuilder().build())
            .header("Authorization", "Bearer " + token())
            .header("Cache-Control", "no-cache")
            .header("request-time", Long.toString(System.currentTimeMillis()))
            .header("x-app", "1.4.3");
        if (body != null) builder.post(RequestBody.create(body.toString(), JSON));
        return builder.build();
    }

    private synchronized void pace() throws InterruptedException {
        long wait = 220 - (System.currentTimeMillis() - previousRequest);
        if (wait > 0) Thread.sleep(wait);
        previousRequest = System.currentTimeMillis();
    }

    private JSONObject execute(Request request) {
        try {
            pace();
            return readJson(getClient().newCall(request).execute());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Consulta Tomato interrompida.", e);
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível conectar à API Tomato.", e);
        }
    }

    public static JSONObject readJson(Response response) {
        try {
            int code = response.code();
            if (code == 401 || code == 403) throw new IllegalStateException("Sessão Tomato inválida, expirada ou acesso recusado. Atualize seu token nas configurações.");
            if (!response.isSuccessful()) throw new IllegalStateException("A API Tomato respondeu HTTP " + code + ".");
            if (response.body() == null) throw new IllegalStateException("Resposta vazia da API Tomato.");
            JSONObject result = new JSONObject(response.body().string());
            if (result.has("status") && Boolean.FALSE.equals(result.opt("status"))) {
                throw new IllegalStateException("A API Tomato recusou a consulta. Verifique sua sessão e o conteúdo solicitado.");
            }
            return result;
        } catch (IOException | JSONException e) {
            throw new IllegalStateException("Resposta da API Tomato em formato inesperado.", e);
        } finally {
            response.close();
        }
    }

    @Override protected Request popularAnimeRequest(int page) { return request("/v2/animes/feed", null); }
    @Override protected Request latestUpdatesRequest(int page) { return popularAnimeRequest(page); }
    @Override protected AnimesPage popularAnimeParse(Response r) { return feed(readJson(r), false); }
    @Override protected AnimesPage latestUpdatesParse(Response r) { return feed(readJson(r), true); }

    private AnimesPage feed(JSONObject root, boolean latest) {
        LinkedHashMap<Integer, JSONObject> items = new LinkedHashMap<>();
        JSONArray sections = root.optJSONArray("data");
        if (sections == null) throw new IllegalStateException("Feed Tomato em formato inesperado.");
        for (int i = 0; i < sections.length(); i++) {
            JSONObject section = sections.optJSONObject(i);
            if (section == null || (latest && section.optInt("type") != 7)) continue;
            JSONArray list = section.optJSONArray("data");
            if (list == null) continue;
            for (int j = 0; j < list.length(); j++) {
                JSONObject item = list.optJSONObject(j);
                if (item == null) continue;
                int id = item.optInt("anime_id", item.optInt("ep_anime_id"));
                if (id > 0 && !items.containsKey(id)) items.put(id, item);
            }
        }
        List<SAnime> animes = new ArrayList<>();
        for (Map.Entry<Integer, JSONObject> entry : items.entrySet()) {
            SAnime card = cardCache.get(entry.getKey());
            if (card == null) {
                JSONObject item = entry.getValue();
                String name = item.optString("anime_name", "").trim();
                if (name.isEmpty()) card = animeFromDetails(execute(request("/v2/anime/" + entry.getKey(), null)));
                else {
                    card = SAnime.Companion.create();
                    card.setUrl("/v2/anime/" + entry.getKey());
                    card.setTitle(name);
                    card.setThumbnail_url(nonempty(item.optString("thumbnail")));
                }
                if (cardCache.size() >= 150) cardCache.clear();
                cardCache.put(entry.getKey(), card);
            }
            animes.add(card);
        }
        return new AnimesPage(animes, false);
    }

    @Override protected Request searchAnimeRequest(int page, String query, AnimeFilterList filters) {
        String input = query.trim();
        if (input.startsWith("id:")) return request("/v2/anime/" + numericId(input.substring(3)), null);
        if (input.startsWith("https://tomatoanimes.com/watch?id=")) {
            return request("/v2/anime/" + numericId(Uri.parse(input).getQueryParameter("id")), null);
        }
        JSONObject body = new JSONObject();
        put(body, "token", token());
        TitleMatcher.RequestedTitle requested = TitleMatcher.requestedTitle(input);
        put(body, "search", requested.season > 0 ? requested.base : input);
        put(body, "content_type", "anime");
        put(body, "page", Math.max(0, page - 1));
        // Keep the query on this specific request, including concurrent searches.
        return request("/v2/content/search", body).newBuilder()
            .tag(SearchQuery.class, new SearchQuery(input)).build();
    }

    private static final class SearchQuery {
        final String text;
        SearchQuery(String text) { this.text = text; }
    }

    @Override protected AnimesPage searchAnimeParse(Response response) {
        SearchQuery query = response.request().tag(SearchQuery.class);
        String path = response.request().url().encodedPath();
        JSONObject root = readJson(response);
        if (path.matches("/v2/anime/[0-9]+")) {
            SAnime anime = animeFromDetails(root);
            if (!path.equals(anime.getUrl())) throw new IllegalStateException("A API retornou uma ficha de outro anime.");
            return new AnimesPage(Collections.singletonList(anime), false);
        }
        if (query == null) throw new IllegalStateException("Não foi possível validar o título da busca Tomato.");
        AnimesPage page = parseSearch(root, query.text);
        int requestedSeason = TitleMatcher.requestedTitle(query.text).season;
        boolean unnumberedGroups = false;
        if (requestedSeason > 0) {
            List<SAnime> verified = new ArrayList<>();
            for (SAnime candidate : page.getAnimes()) {
                String candidatePath = animePath(candidate);
                JSONObject details = execute(request(candidatePath, null));
                SAnime anime = animeFromDetails(details);
                if (!candidatePath.equals(anime.getUrl()) || !TitleMatcher.sameSeries(query.text, anime.getTitle())) continue;
                JSONArray seasons = details.optJSONArray("anime_seasons");
                if (seasons == null) throw new IllegalStateException("Não foi possível verificar a temporada no Tomato.");
                boolean found = false;
                boolean hasSeasonLabels = false;
                for (int i = 0; i < seasons.length(); i++) {
                    JSONObject season = seasons.optJSONObject(i);
                    if (season == null || season.optInt("season_id") <= 0) continue;
                    int label = TitleMatcher.seasonLabel(season.optString("season_name"));
                    if (label > 0) hasSeasonLabels = true;
                    if (label == requestedSeason) found = true;
                }
                if (hasSeasonLabels && !found) continue;
                if (hasSeasonLabels) anime.setUrl(candidatePath + "?season=" + requestedSeason);
                else unnumberedGroups = true; // Keep the exact series; user chooses its named arc/group.
                verified.add(anime);
            }
            page = new AnimesPage(verified, false);
        }
        if (page.getAnimes().size() > 1) {
            notifySearchMessage("Mais de um anime tem esse título no Tomato. Use o ID para escolher o correto.");
            return new AnimesPage(Collections.emptyList(), false);
        }
        if (page.getAnimes().isEmpty()) notifySearchMessage(
            "Nenhum título correspondente encontrado no Tomato. Tente o nome exato ou o ID do Tomato.");
        else if (unnumberedGroups) notifySearchMessage(
            "Série encontrada. O Tomato usa nomes de arcos/grupos; escolha a temporada desejada na lista.");
        return page;
    }

    private synchronized void notifySearchMessage(String message) {
        Context context = appContext;
        long now = System.currentTimeMillis();
        if (context == null || now - lastMissingNotice < 5000) return;
        lastMissingNotice = now;
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }

    public static AnimesPage parseSearch(JSONObject root, String query) {
        JSONArray results = root.optJSONArray("result");
        if (results == null) throw new IllegalStateException("Busca Tomato em formato inesperado.");
        List<SAnime> animes = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.optJSONObject(i);
            if (item == null) continue;
            int id = item.optInt("id");
            String name = item.optString("name", "").trim();
            if (id <= 0 || !TitleMatcher.sameSeries(query, name) || !seen.add(id)) continue;
            SAnime anime = SAnime.Companion.create();
            anime.setUrl("/v2/anime/" + id);
            anime.setTitle(name);
            anime.setThumbnail_url(nonempty(item.optString("image")));
            anime.setGenre(nonempty(item.optString("tags")));
            animes.add(anime);
        }
        // The producer has not supplied a reliable next-page marker. Do not loop on a full list.
        return new AnimesPage(animes, false);
    }

    private static final class SeasonSelection {
        final int number;
        SeasonSelection(int number) { this.number = number; }
    }

    @Override public Request animeDetailsRequest(SAnime anime) {
        return request(animePath(anime), null).newBuilder()
            .tag(SeasonSelection.class, new SeasonSelection(selectedSeason(anime))).build();
    }
    @Override protected SAnime animeDetailsParse(Response r) {
        SeasonSelection selection = r.request().tag(SeasonSelection.class);
        SAnime anime = animeFromDetails(readJson(r));
        if (selection != null && selection.number > 0) anime.setUrl(anime.getUrl() + "?season=" + selection.number);
        return anime;
    }

    public static SAnime animeFromDetails(JSONObject root) {
        JSONObject detail = root.optJSONObject("anime_details");
        if (detail == null) throw new IllegalStateException("Ficha Tomato em formato inesperado.");
        int id = detail.optInt("anime_id");
        String title = detail.optString("anime_name", "").trim();
        if (id <= 0 || title.isEmpty()) throw new IllegalStateException("Ficha Tomato sem ID ou título.");
        SAnime anime = SAnime.Companion.create();
        anime.setUrl("/v2/anime/" + id);
        anime.setTitle(title);
        anime.setDescription(nonempty(detail.optString("anime_description")));
        anime.setGenre(nonempty(detail.optString("anime_genre")));
        anime.setThumbnail_url(nonempty(detail.optString("anime_cover_url", detail.optString("anime_cape_url"))));
        anime.setStatus(SAnime.UNKNOWN);
        anime.setInitialized(true);
        return anime;
    }

    @Override protected Request episodeListRequest(SAnime anime) { return animeDetailsRequest(anime); }
    @Override protected List<SEpisode> episodeListParse(Response response) {
        SeasonSelection selection = response.request().tag(SeasonSelection.class);
        JSONObject root = readJson(response);
        JSONArray seasons = root.optJSONArray("anime_seasons");
        if (seasons == null) throw new IllegalStateException("Lista de temporadas Tomato em formato inesperado.");
        String language = getSourcePreferences().getString(LANGUAGE_KEY, "Todos");
        List<SEpisode> episodes = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < seasons.length(); i++) {
            JSONObject season = seasons.optJSONObject(i);
            if (season == null) continue;
            int id = season.optInt("season_id");
            if (id <= 0) continue;
            String dub = season.optInt("season_dubbed") == 1 ? "Dublado" : "Legendado";
            if (!"Todos".equals(language) && !dub.equals(language)) continue;
            int number = season.optInt("season_number", 1);
            int seasonLabel = TitleMatcher.seasonLabel(season.optString("season_name"));
            if (selection != null && selection.number > 0 && selection.number != seasonLabel) continue;
            if (seasonLabel > 0) number = seasonLabel;
            String groupName = season.optString("season_name", "").replaceFirst(
                "(?i)^\\s*\\((?:legendado|dublado)\\)\\s*", "").trim();
            for (int page = 0; page < 200; page++) {
                JSONObject body = new JSONObject();
                put(body, "token", token()); put(body, "page", page); put(body, "order", "ASC");
                JSONObject batch = execute(request("/season/" + id + "/episodes", body));
                JSONArray data = batch.optJSONArray("data");
                if (data == null) throw new IllegalStateException("Lista de episódios Tomato em formato inesperado.");
                int before = episodes.size();
                for (int j = 0; j < data.length(); j++) {
                    JSONObject ep = data.optJSONObject(j);
                    if (ep == null || ep.optInt("ep_id") <= 0 || !seen.add(ep.optInt("ep_id"))) continue;
                    SEpisode episode = episodeFromJson(ep, number, dub);
                    if (seasonLabel == 0 && !groupName.isEmpty()) {
                        episode.setScanlator(dub + " • " + groupName);
                        episode.setName("[" + dub + "] " + groupName + " • " + ep.optString("ep_name", "Episódio " + episode.getEpisode_number()));
                    }
                    episodes.add(episode);
                }
                if (data.length() < 25 || before == episodes.size()) break;
                if (page == 199) throw new IllegalStateException("Lista de episódios excedeu o limite de páginas. Tente outra temporada.");
            }
        }
        Collections.reverse(episodes);
        return episodes;
    }

    public static SEpisode episodeFromJson(JSONObject ep, int season, String language) {
        int id = ep.optInt("ep_id");
        if (id <= 0) throw new IllegalStateException("Episódio Tomato sem ID.");
        float number = (float) ep.optDouble("ep_number", 0);
        String name = ep.optString("ep_name", "").trim();
        if (name.isEmpty()) name = "Episódio " + number;
        SEpisode episode = SEpisode.Companion.create();
        episode.setUrl("/v2/anime/episode/" + id + "/stream");
        episode.setName("[" + language + "] T" + season + " • " + name);
        episode.setEpisode_number(number);
        episode.setScanlator(language + " • Temporada " + season);
        return episode;
    }

    @Override protected Request videoListRequest(SEpisode ep) { return request(episodePath(ep), null); }
    @Override protected List<Video> videoListParse(Response response) { return videosFromJson(readJson(response), ""); }
    @Override public Observable<List<Video>> fetchVideoList(SEpisode episode) {
        return Observable.fromCallable(() -> sortedVideos(execute(videoListRequest(episode)), episode.getScanlator()));
    }
    @Override public Object getVideoList(SEpisode episode, kotlin.coroutines.Continuation<? super List<Video>> continuation) {
        return sortedVideos(execute(videoListRequest(episode)), episode.getScanlator());
    }

    private List<Video> sortedVideos(JSONObject root, String scanlator) {
        String preferred = getSourcePreferences().getString(QUALITY_KEY, "1080p");
        String language = scanlator == null ? "" : scanlator.split(" • ")[0];
        List<Video> videos = videosFromJson(root, language);
        videos.sort((a, b) -> Boolean.compare(b.getQuality().contains(preferred), a.getQuality().contains(preferred)));
        return videos;
    }

    public static List<Video> videosFromJson(JSONObject root, String language) {
        JSONObject streams = root.optJSONObject("streams");
        if (streams == null) throw new IllegalStateException("A API Tomato não retornou os streams deste episódio.");
        List<Video> videos = new ArrayList<>();
        String prefix = language == null || language.isEmpty() ? "" : language + " • ";
        Headers mediaHeaders = new Headers.Builder().set("User-Agent", "tomato-android").set("Accept", "*/*").build();
        String[] keys = {"fhd", "mhd", "shd"};
        String[] resolutions = {"1080p", "720p", "480p"};
        for (int i = 0; i < keys.length; i++) {
            if (streams.isNull(keys[i])) continue;
            String url = streams.optString(keys[i], "").trim();
            HttpUrl parsed = HttpUrl.parse(url);
            if (parsed == null || !"https".equals(parsed.scheme())) continue;
            // Preserve the whole signed query. Never forward the account's Bearer to a media host.
            videos.add(new Video(url, prefix + resolutions[i], url, (Uri) null, mediaHeaders));
        }
        if (videos.isEmpty()) throw new IllegalStateException("Nenhum vídeo disponível para este episódio na API Tomato.");
        return videos;
    }

    @Override public String getAnimeUrl(SAnime anime) {
        String path = animePath(anime);
        return "https://tomatoanimes.com/watch?id=" + path.substring(path.lastIndexOf('/') + 1);
    }
    private static String animePath(SAnime anime) {
        String path = anime.getUrl();
        if (!path.matches("/v2/anime/[0-9]+(?:\\?season=[1-9][0-9]{0,2})?")) throw new IllegalArgumentException("ID de anime Tomato inválido.");
        return path.split("\\?", 2)[0];
    }
    private static int selectedSeason(SAnime anime) {
        animePath(anime); // Validate the entire internal URL before reading its selection.
        String[] parts = anime.getUrl().split("\\?season=", 2);
        return parts.length == 2 ? Integer.parseInt(parts[1]) : 0;
    }
    private static String episodePath(SEpisode episode) {
        String path = episode.getUrl();
        if (!path.matches("/v2/anime/episode/[0-9]+/stream")) throw new IllegalArgumentException("ID de episódio Tomato inválido.");
        return path;
    }
    private static String numericId(String input) {
        if (input == null || !input.trim().matches("[0-9]+")) throw new IllegalArgumentException("Informe um ID numérico Tomato.");
        return input.trim();
    }
    private static String nonempty(String value) { return value == null || value.isEmpty() || "null".equals(value) ? null : value; }
    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); } catch (JSONException e) { throw new IllegalArgumentException(e); }
    }
}
