class TTarget {
    static int callee() {
        return 7                                       // L3
    }
    int action() {
        int x = 1                                      // L6
        int y = (int) Mid.wrap({ callee() }) + 1        // L7  <- lands mid-line on return
        int z = y + 1                                  // L8
        println "z=$z"                                 // L9
        return z                                       // L10
    }
    static void main(String[] args) {
        def m = TTarget.class.getMethod("action")
        m.invoke(new TTarget())                        // L15
    }
}
