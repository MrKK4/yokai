## YYYY-MM-DD - Missing Secure Screen in BaseThemedActivity and CrashActivity
**Vulnerability:** Secure screen preference (incognito mode/privacy settings) is bypassed on CrashActivity and classes inheriting BaseThemedActivity (BiometricActivity, OAuth Login Activities).
**Learning:** Activities that don't inherit from BaseActivity might bypass app-wide security screen flag.
**Prevention:** All activities including exception handler UI and authentication flows must call SecureActivityDelegate.setSecure(this) in onCreate.
