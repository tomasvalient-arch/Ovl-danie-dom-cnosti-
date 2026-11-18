package android.content;

/** Minimálna náhrada Android rozhrania pre testy bez Androidu. */
public interface SharedPreferences {
    String getString(String key, String defValue);

    Editor edit();

    interface Editor {
        Editor putString(String key, String value);

        void apply();
    }
}
