import org.moqui.impl.context.ExecutionContextImpl

// post-commit rule (run-on-error): a held create/delete stopped the service with an error carrying the "request sent" text.
// Replace that error with a plain message so the Tools screens, the Request page and API callers all see it as information.
Map shared = ((ExecutionContextImpl) ec).contextStack.getSharedMap()
String held = shared.remove("ccr.heldMsg")
if (held && ec.message.errors == [held]) {
    // clearErrors() would keep the text as a red "danger" message; pushErrors() sets the error aside without showing it
    ec.message.pushErrors()
    ec.message.addMessage(held)
}
