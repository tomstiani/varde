package no.varde.app.inbox

enum class Problem(val explanation: String) {
    CONFIGURATION("Enter a name, HTTPS URL and credential. URLs cannot contain a username, password, query or fragment."),
    AUTHENTICATION("The credential is invalid or revoked. Update it in connection settings."),
    PERMISSION("This credential cannot read alerts. Use an installation credential."),
    NOT_FOUND("This alert is unavailable."),
    NETWORK("The backend could not be reached securely. Check your connection and HTTPS certificate, then retry."),
    REDIRECT("The backend redirected the request. Correct its URL in connection settings."),
    RESPONSE("The backend returned an unsupported response."),
    SETTINGS("Secure connection settings could not be read or saved."),
}

class InboxFailure(val problem: Problem) : Exception(problem.explanation)
