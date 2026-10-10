import eu.kanade.tachiyomi.animeextension.pt.tomatobr.TitleMatcher;

public class TitleMatcherTest {
    private static int checks;
    private static void check(boolean expected, String query, String result) {
        boolean actual = TitleMatcher.matches(query, result);
        if (actual != expected) throw new AssertionError(query + " / " + result + ": " + actual);
        checks++;
    }
    private static void season(String query, String base, int number) {
        TitleMatcher.RequestedTitle result = TitleMatcher.requestedTitle(query);
        if (!result.base.equals(base) || result.season != number) throw new AssertionError(query + ": " + result.base + "/" + result.season);
        checks++;
    }
    private static void series(boolean expected, String query, String result) {
        if (TitleMatcher.sameSeries(query, result) != expected) throw new AssertionError(query + " / " + result);
        checks++;
    }
    private static void label(String name, int expected) {
        if (TitleMatcher.seasonLabel(name) != expected) throw new AssertionError("Group " + name);
        checks++;
    }
    public static void main(String[] args) {
        check(true, "BIRDIE WING: Golf Girls' Story", "BIRDIE WING -Golf Girls' Story-");
        check(true, "koori no jouheki", "Koori no Jouheki");
        check(true, "Pokémon", "POKEMON");
        check(true, "JoJo’s Bizarre Adventure", "JoJo's Bizarre Adventure");
        check(true, "  Koori   no Jouheki! ", "Koori no Jouheki");
        check(true, "ＮＡＲＵＴＯ", "naruto");
        check(true, "Fate/stay night", "Fate stay night");
        check(false, "Sora-iro Utility × BIRDIE WING: Golf Girls' Story Mini Anime", "BIRDIE WING -Golf Girls' Story-");
        check(false, "BIRDIE WING: Golf Girls' Story Season 2", "BIRDIE WING: Golf Girls' Story");
        check(false, "Koori no Jouheki 2nd Season", "Koori no Jouheki");
        check(false, "Naruto Shippuden", "Naruto");
        check(false, "Naruto", "Boruto: Naruto Next Generations");
        check(false, "Orange", "Kimagure Orange Road");
        check(false, "Dragon Ball Daima", "Dragon Ball Z");
        check(false, "One Piece Film: Red", "One Piece");
        check(false, "Birdie", "BIRDIE WING: Golf Girls' Story");
        check(false, "Anime Inexistente 987654", "One Piece");
        check(false, "", "");
        check(false, "!!!", "???");
        check(false, null, "Naruto");
        check(false, "Naruto", null);
        check(false, "Attack on Titan", "Shingeki no Kyojin");
        check(false, "Kanojo no Tomodachi", "Girlfriend, Girlfriend");
        check(false, "My Girlfriend's Friend", "Girlfriend, Girlfriend");
        check(false, "Kanojo no Tomodachi", "Kanojo mo Kanojo");
        series(false, "Kanojo no Tomodachi 2nd Season", "Girlfriend, Girlfriend");
        season("Koori no Jouheki 2nd Season", "koori no jouheki", 2);
        season("Koori no Jouheki Season 2", "koori no jouheki", 2);
        season("Koori no Jouheki Temporada 2", "koori no jouheki", 2);
        season("Koori no Jouheki 2ª Temporada", "koori no jouheki", 2);
        season("Koori no Jouheki Season II", "koori no jouheki", 2);
        season("Mob Psycho 100", "mob psycho 100", 0);
        season("86", "86", 0);
        season("Koori no Jouheki 2", "koori no jouheki 2", 0);
        season("One Piece Film Red", "one piece film red", 0);
        season("Naruto Season 2 Part 2", "naruto season 2 part 2", 0);
        season("Naruto Season 0", "naruto season 0", 0);
        season("Naruto Season 9999999999999", "naruto season 9999999999999", 0);
        series(true, "Koori no Jouheki 2nd Season", "koori no jouheki");
        series(true, "BIRDIE WING: Golf Girls' Story Season 2", "BIRDIE WING -Golf Girls' Story-");
        series(false, "Koori no Jouheki 2nd Season", "Koori Zokusei Danshi to Cool na Douryou Joshi");
        series(false, "Naruto Shippuden Season 2", "Naruto");
        series(false, "Koori no Jouheki 2nd Season", "Koori no Jouheki 3rd Season");
        series(false, "Sora-iro Utility × BIRDIE WING: Golf Girls' Story Mini Anime Season 2", "BIRDIE WING -Golf Girls' Story-");
        series(false, "One Piece Film Red Season 2", "One Piece");
        series(false, "Koori no Jouheki Season 2 Part 2", "Koori no Jouheki");
        label("(Legendado) Season I", 1);
        label("(Dublado) Season II", 2);
        label("(Legendado) 2ª Temporada", 2);
        label("(Legendado) Season 3", 3);
        label("(Legendado) O Filme: Mugen Train", 0);
        label("(Legendado) Entertainment District Arc", 0);
        label("Movie Season 2", 0);
        System.out.println(checks + " regressões passaram.");
    }
}
