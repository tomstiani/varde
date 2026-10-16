package no.varde.app.inbox

enum class Problem(val explanation: String) {
    CONFIGURATION("Enter a name, HTTPS URL and credential. URLs cannot contain a username, password, query or fragment." + (EndpointPolicy.usbHint?.let { " $it" } ?: "")),
    DUPLICATE_NAME("Each connection needs a distinct friendly name."),
    DUPLICATE_URL("This backend URL is already configured."),
    AUTHENTICATION("The credential is invalid or revoked. Update it in connection settings."),
    PERMISSION("This credential cannot read alerts. Use an installation credential."),
    NOT_FOUND("This alert is unavailable."),
    NETWORK("The backend could not be reached. Check your network and backend availability, then retry."),
    TLS("The secure connection could not be verified. Check the backend URL and its Android-trusted HTTPS certificate."),
    HISTORY_CURSOR("This history position is no longer valid. Refresh all connections to start a new browsing session."),
    REDIRECT("The backend redirected the request. Correct its URL in connection settings."),
    RESPONSE("The backend returned an unsupported response. Check its URL and version, then refresh."),
    SETTINGS("Secure connection settings could not be read or saved.");

    val affectsConnection: Boolean get() = when (this) {
        AUTHENTICATION, PERMISSION, NETWORK, TLS, HISTORY_CURSOR, REDIRECT, RESPONSE -> true
        else -> false
    }
}

class InboxFailure(val problem: Problem) : Exception(problem.explanation)
