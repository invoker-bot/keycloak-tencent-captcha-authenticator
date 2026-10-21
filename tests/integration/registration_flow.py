"""Add a mandatory CAPTCHA before the copied Keycloak registration form."""
from urllib.parse import quote


def protect_registration(admin_json, realm, alias):
    """Copy the bound flow, preserve its actions, then bind the verified copy.

    admin_json uses the AcceptanceVerifier REST calling convention. The alias
    must be new, so a partial run can never silently reuse an unsafe flow.
    """
    prefix = "/admin/realms/" + quote(realm, safe="")
    current = admin_json("GET", prefix)
    original = current["registrationFlow"]
    flow = prefix + "/authentication/flows/" + quote(alias, safe="")
    admin_json("POST", prefix + "/authentication/flows/" + quote(original, safe="") + "/copy",
               {"newName": alias}, expected=(201,))
    admin_json("POST", flow + "/executions/execution", {"provider": "tencent-captcha"}, expected=(201,))
    executions = admin_json("GET", flow + "/executions")
    captcha = next(e for e in executions if e.get("providerId") == "tencent-captcha" and e["level"] == 0)
    captcha["requirement"] = "REQUIRED"
    admin_json("PUT", flow + "/executions", captcha, expected=(204,))
    for _ in range(len(executions)):
        executions = admin_json("GET", flow + "/executions")
        if executions[0]["id"] == captcha["id"]:
            break
        admin_json("POST", prefix + "/authentication/executions/" + captcha["id"] + "/raise-priority",
                   expected=(204,))
    executions = admin_json("GET", flow + "/executions")
    first = executions[0]
    assert first["id"] == captcha["id"] and first["requirement"] == "REQUIRED" and first["level"] == 0
    assert any(e.get("providerId") == "registration-page-form" and e["requirement"] == "REQUIRED"
               for e in executions), "required registration form missing"
    # Update only the binding, retaining all other realm settings.
    admin_json("PUT", prefix, {"registrationFlow": alias}, expected=(204,))
    assert admin_json("GET", prefix)["registrationFlow"] == alias
    return {"previousRegistrationFlow": original, "registrationFlow": alias,
            "captchaExecution": captcha["id"]}
