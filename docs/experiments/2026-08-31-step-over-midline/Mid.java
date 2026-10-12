import java.util.concurrent.Callable;
// Compiled with -g:none: no LineNumberTable, the way @Transactional's synthetic
// callback frame looks to JDI.
public class Mid {
    public static Object wrap(Callable<Object> body) throws Exception {
        Object r = body.call();
        return r;
    }
}
