class RTarget {
    static int callee() {
        return 7                                  // L3
    }
    int action() {
        int x = 1                                 // L6
        int y = callee() + 1                      // L7  <- lands mid-line on return
        int z = y + 1                             // L8
        println "z=$z"                            // L9
        return z                                  // L10
    }
    static void main(String[] args) {
        // Invoke the action reflectively, so its caller is a native frame -- the
        // shape a Grails controller action is called in (ReflectionInvoker.invoke).
        def m = RTarget.class.getMethod("action")
        m.invoke(new RTarget())                   // L16
    }
}
