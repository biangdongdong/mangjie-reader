package com.mangjie.reader.net;

/**
 * 番茄（fanqienovel）书名的字体混淆还原。
 *
 * 它的 book_list 接口把书名里的一部分字换成 Unicode 私用区码位（U+E3F3 起那段），
 * 网页靠自带字体渲染，纯文本拿到就是一片方块（真机上读屏念不准、屏幕显示 □）。
 * 同一码位在不同书里对应同一个字（用 420 本的书名与 book_info 的真实书名逐字对齐验证过，
 * 303 个码位零冲突），所以这里放一张静态表，显示之前先换回真字。
 *
 * 表会随番茄换字体失效：遇到表里没有的码位原样保留，不会把字吃掉。
 */
public final class PuaText {
    private PuaText() {}

    /** 每项是 4 位十六进制码位 + 对应汉字，逗号分隔 */
    private static final String TABLE = "e472全,e4d5学,e46c心,e3fb理,e462解,e4d1者,e519三,e420要,e40e我,e473这,e48c样,e3f3的,e41c人,e520才,e431小,e4f1山,e49b当,e4fb个,e42e么,e545成,e517了,e499花,e406她,e512从,e480地,e528来,e3ea十,e4c5年,e50b女,e52b长,e3fa风,e559行,e4e2家,e54c天,e458把,e4b8命,e471民,e511不,e4fc开,e553那,e443就,e552路,e4e4失,e525被,e440大,e3fd老,e449实,e411员,e4fa工,e47f和,e48d总,e526一,e539过,e534后,e489你,e42b时,e4c8出,e426男,e405主,e465作,e4a3友,e53d子,e508日,e400常,e554别,e54d他,e521儿,e54a上,e475果,e51b些,e524美,e453们,e4c7认,e416教,e509信,e4b6动,e52c对,e53a世,e3fe也,e421少,e3f6爱,e540书,e4ab真,e4e9金,e412太,e49f重,e4a5生,e51e夫,e43f又,e49d手,e45f新,e47c说,e4fe好,e40a情,e4aa回,e49e4,e460外,e4d7分,e442远,e50a与,e4bf高,e4a2力,e466前,e438下,e4c0有,e523两,e4bd特,e437神,e4f0原,e541之,e49c名,e51f会,e4749,e4b00,e43d度,e423门,e535往,e459事,e500都,e4b1点,e4de觉,e498能,e496乐,e4f3白,e3f7万,e464只,e54b想,e456是,e4a9本,e502可,e550文,e44f到,e4ad师,e4b3亲,e54e妈,e53f在,e536打,e450道,e432向,e4ac入,e514机,e527公,e42d走,e493头,e4ea叫,e484场,e43a车,e486像,e41a已,e3f0月,e40c光,e45c界,e3f5知,e4f2海,e447通,e479母,e4dc但,e457无,e55b难,e4f5见,e4e3让,e469经,e4d0明,e551而,e4dd比,e537结,e4f9第,e4ec报,e454加,e4d6还,e4ff用,e436d,e40da,e3e9y,e505次,e4bc做,e4c2为,e503同,e41d如,e47d任,e4cc正,e45e发,e3eb现,e4e1死,e419军,e41e变,e41f得,e40f呢,e409法,e4ce感,e3ed便,e428A,e49al,e3ffp,e4bah,e4e8I,e48a使,e3ec快,e413每,e452吃,e4f4很,e4f7直,e542定,e4eb身,e522眼,e45b多,e408将,e463孩,e4df以,e532相,e530许,e446给,e4c6马,e518记,e4c1B,e3fcO,e44eS,e50d满,e41bU,e4d3F,e42f何,e415受,e53b气,e54f1,e47a8,e5043,e533色,e42a国,e439间,e506四,e4cd方,e4cf所,e430口,e4fd岁,e46e父,e547带,e548着,e448起,e544拉,e50c笑,e435T,e4a6代,e52e看,e53c7,e51dN,e407s,e543v,e558t,e501于,e4a4然,e4e72,e402平,e546进,e455P,e53e条,e46f等,e404最,e4e6L,e529立,e425m,e40bo,e51cb,e3ef却,e4c4自,e43cG,e45a西,e4a8里,e510什,e4b5种,e42c中,e497关,e3f2水,e444写,e476安,e4ef再,e4d8意,e483战,e401克,e4ca至,e4ed听,e3f1物,e515此,e3e8体,e461活,e4e5士,e549东,e3ee话,e5566,e490性,e422斯,e495应,e531因,e424电,e478i,e43bf,e481C,e487c,e46d告,e427没,e492音,e3f4放,e433问,e414望";

    private static java.util.Map<Integer, String> map;

    private static void init() {
        if (map != null) return;
        map = new java.util.HashMap<>();
        for (String seg : TABLE.split(",")) {
            if (seg.length() < 5) continue;
            try { map.put(Integer.parseInt(seg.substring(0, 4), 16), seg.substring(4)); }
            catch (Exception ignored) {}
        }
    }

    public static boolean hasPua(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            int c = s.charAt(i);
            if (c >= 0xE000 && c <= 0xF8FF) return true;
        }
        return false;
    }

    /** 把私用区码位换回真字；表里没有的保持原样 */
    public static String fix(String s) {
        if (s == null || s.isEmpty() || !hasPua(s)) return s;
        init();
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xE000 && c <= 0xF8FF) {
                String rep = map.get((int) c);
                if (rep != null) {
                    if (sb == null) sb = new StringBuilder(s.length()).append(s, 0, i);
                    sb.append(rep);
                    continue;
                }
            }
            if (sb != null) sb.append(c);
        }
        return sb == null ? s : sb.toString();
    }
}
