# Runtime compatibility profile

RWmiao does not bind variant compatibility to a package name. At startup it:

1. discovers the namespace from `appFramework.InGameActivity`;
2. prefers canonical Rusted Warfare 1.15 symbols when they exist;
3. otherwise resolves source-remapped `class_N`, `field_N`, and `method_N`
   symbols by declaration order, descriptor, and static/instance shape;
4. accepts a preserved semantic member name only when its full signature is
   unique, avoiding ambiguous type-only guesses.

`build.ps1` regenerates `app/src/main/assets/rwmiao-symbols.map` from the
official 1.15 smali baseline. Package names and concrete member numbers are not
stored in the profile.

`verify_variant.py` is a local-only checker and is deliberately ignored by
`.gitignore`; it is not uploaded to the repository and is never packaged into
the APK.

Example static checks after decoding a candidate APK with apktool:

```powershell
python tools/compat-mapping/verify_variant.py `
  --smali-root D:/decoded/apktool/smali `
  --prefix com.example.rustedwarfare
```

The verifier checks every class referenced through `RWmiaoModule.target(...)`
and asserts the engine, player, UI action-list, and native action proxy
sentinels used to bootstrap the module.
