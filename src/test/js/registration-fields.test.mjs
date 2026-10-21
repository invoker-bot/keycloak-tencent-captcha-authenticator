import test from "node:test";
import assert from "node:assert/strict";
import { requiredRegistrationContext } from "../../../examples/keycloakify/registration-fields.mjs";

const attribute = (name, required) => ({ name, required, value: "", validators: {}, annotations: {} });
function registration(attributes) {
    return { pageId: "register.ftl", passwordRequired: true, url: { registrationAction: "/registration" },
        profile: { attributesByName: Object.fromEntries(attributes.map(a => [a.name, a])) } };
}

test("registration hides optional names and custom profile fields before validation", () => {
    const input = registration([attribute("username", true), attribute("email", true),
        attribute("firstName", false), attribute("lastName", false), attribute("company", false)]);
    const result = requiredRegistrationContext(input);
    assert.deepEqual(Object.keys(result.profile.attributesByName), ["username", "email"]);
    assert.equal(result.passwordRequired, true);
    assert.equal(result.url, input.url);
    assert.deepEqual(Object.keys(input.profile.attributesByName), ["username", "email", "firstName", "lastName", "company"]);
});

test("server-required names and conditional custom attributes remain visible", () => {
    const required = registration([attribute("firstName", true), attribute("lastName", true), attribute("company", true)]);
    assert.deepEqual(Object.keys(requiredRegistrationContext(required).profile.attributesByName), ["firstName", "lastName", "company"]);
    const optional = registration([attribute("firstName", false), attribute("lastName", false), attribute("company", false)]);
    assert.deepEqual(Object.keys(requiredRegistrationContext(optional).profile.attributesByName), []);
});

test("optional email is hidden when Keycloak does not require it", () => {
    const input = registration([attribute("username", true), attribute("email", false)]);
    assert.deepEqual(Object.keys(requiredRegistrationContext(input).profile.attributesByName), ["username"]);
});

test("profile update, broker review, and other pages retain their optional fields", () => {
    for (const pageId of ["login-update-profile.ftl", "idp-review-user-profile.ftl", "update-email.ftl", "login.ftl"]) {
        const input = { ...registration([attribute("firstName", false)]), pageId };
        assert.equal(requiredRegistrationContext(input), input);
    }
});

test("field metadata and ordering are preserved and server required flags are not guessed", () => {
    const input = registration([attribute("department", true), attribute("email", true),
        attribute("unknown", undefined), attribute("malformed", "true")]);
    const result = requiredRegistrationContext(input);
    assert.deepEqual(Object.keys(result.profile.attributesByName), ["department", "email"]);
    assert.equal(result.profile.attributesByName.department, input.profile.attributesByName.department);
});
