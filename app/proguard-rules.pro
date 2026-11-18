# Pravidlá pre R8 (release build appky Domov).

# Názvy tried a enumov appky ponechať: niektoré sa ukladajú do nastavení / záloh ako text (enum.name)
# a v hláškach sa zobrazuje názov chyby (javaClass.simpleName). Kód sa aj tak zmenší a zoptimalizuje.
-keepnames class sk.domov.ovladanie.** { *; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}

# čitateľné výpisy chýb (stack trace)
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# ZXing (generovanie QR kódu) – nepotrebné časti môžu chýbať
-dontwarn com.google.zxing.**
