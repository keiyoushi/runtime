# Applied to every app or extension that bundles the contract.

# RuntimeModule matches contract methods to the runtime's methods by name. Keeping them on the
# interface keeps them on the runtime's implementation too.
-keep interface * extends keiyoushi.lib.runtime.RuntimeContract { <methods>; }
# Their @Throws: a proxy wraps checked exceptions its method doesn't declare
-keepattributes Exceptions
