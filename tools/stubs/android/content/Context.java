package android.content;

/** Minimálna náhrada Android triedy, aby sa Device.kt dal skompilovať v testoch bez Androidu. */
public abstract class Context {
    public static final int MODE_PRIVATE = 0;

    public abstract SharedPreferences getSharedPreferences(String name, int mode);
}
