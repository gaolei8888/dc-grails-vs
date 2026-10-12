public class Target {
    static int callee() {
        return 7;                       // L3
    }
    public static void main(String[] args) {
        int x = 1;                      // L6
        int y = callee() + 1;           // L7  <- returning from callee lands mid-line
        int z = y + 1;                  // L8
        System.out.println("z=" + z);   // L9
    }
}
