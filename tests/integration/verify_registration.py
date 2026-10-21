"""HTTP registration gates, using a real provider and the isolated TLS API fixture."""
import html
import http.cookiejar
import re
import urllib.error
import urllib.parse
import urllib.request
import uuid

from configure_and_verify import AcceptanceVerifier, PROTECTED_REALM, CLIENT_ID
from registration_flow import protect_registration


def verify(base_url):
    v = AcceptanceVerifier(base_url)
    v.configure()
    prefix = '/admin/realms/' + PROTECTED_REALM
    v._admin_json('PUT', prefix, {'registrationAllowed': True}, expected=(204,))
    profile = v._admin_json('GET', prefix + '/users/profile')
    for attribute in profile['attributes']:
        if attribute['name'] in ('firstName', 'lastName'):
            attribute.pop('required', None)
    v._admin_json('PUT', prefix + '/users/profile', profile)
    binding = protect_registration(v._admin_json, PROTECTED_REALM, 'registration-with-captcha')
    executions = v._admin_json('GET', prefix + '/authentication/flows/registration-with-captcha/executions')
    form_id = next(e['id'] for e in executions if e.get('providerId') == 'registration-page-form')
    registration_url = base_url + '/realms/' + PROTECTED_REALM + '/protocol/openid-connect/registrations?' + urllib.parse.urlencode({
        'client_id': CLIENT_ID, 'redirect_uri': base_url + '/callback', 'response_type': 'code', 'scope': 'openid'})
    username = 'integration-register-' + uuid.uuid4().hex[:12]
    fields = {'username': username, 'email': username + '@example.invalid', 'password': 'Integration-password-731!',
              'password-confirm': 'Integration-password-731!'}

    class Browser:
        def __init__(self):
            self.cookies = http.cookiejar.CookieJar()
            self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))

        def request(self, url, data=None):
            # Keycloak treats loopback as secure, as browsers do. urllib does not.
            assert urllib.parse.urlparse(base_url).hostname in ('127.0.0.1', 'localhost')
            for cookie in self.cookies:
                cookie.secure = False
            try:
                res = self.opener.open(url, None if data is None else urllib.parse.urlencode(data).encode(), timeout=15)
            except urllib.error.HTTPError as error:
                res = error
            with res:
                return res.read().decode()

        def challenge(self):
            page = self.request(registration_url)
            assert 'data-captcha-challenge' in page and 'kc-register-form' not in page
            action = html.unescape(re.search(r'data-login-action="([^"]+)"', page).group(1))
            assert '/login-actions/registration?' in action
            return action

    def no_user():
        v.refresh_admin_token()
        assert v._admin_json('GET', prefix + '/users?' + urllib.parse.urlencode({'username': username, 'exact': 'true'})) == []

    results = {'registrationCaptchaRequired': binding['registrationFlow'] == 'registration-with-captcha'}
    for name, proof in [('missingProof', {}), ('forgedProof', {'ticket': 'forged', 'randstr': 'fake'}),
                        ('disasterProof', {'ticket': 'trerror_fake', 'randstr': 'fake'})]:
        browser = Browser()
        page = browser.request(browser.challenge(), {**fields, **proof})
        assert 'data-captcha-challenge' in page and 'kc-register-form' not in page, name
        no_user()
        results[name + 'Rejected'] = True
    for name, execution in [('formExecution', form_id), ('missingExecution', None), ('unknownExecution', str(uuid.uuid4()))]:
        browser = Browser()
        action = urllib.parse.urlparse(browser.challenge())
        query = dict(urllib.parse.parse_qsl(action.query))
        if execution is None:
            query.pop('execution')
        else:
            query['execution'] = execution
        page = browser.request(urllib.parse.urlunparse(action._replace(query=urllib.parse.urlencode(query))), fields)
        assert 'kc-register-form' not in page, name
        no_user()
        results[name + 'BypassRejected'] = True

    browser = Browser()
    ticket = 'integration-valid-' + uuid.uuid4().hex
    page = browser.request(browser.challenge(), {'ticket': ticket, 'randstr': 'integration-randstr'})
    assert 'id="kc-register-form"' in page, 'accepted server proof must unlock registration'
    form_action = html.unescape(re.search(r'<form[^>]+id="kc-register-form"[^>]+action="([^"]+)"', page).group(1))
    browser.request(form_action, fields)
    v.refresh_admin_token()
    users = v._admin_json('GET', prefix + '/users?' + urllib.parse.urlencode({'username': username, 'exact': 'true'}))
    assert len(users) == 1 and users[0]['username'] == username
    results['serverAcceptedProofCreatesUser'] = True
    replay = Browser()
    page = replay.request(replay.challenge(), {'ticket': ticket, 'randstr': 'integration-randstr'})
    assert 'data-captcha-challenge' in page and 'kc-register-form' not in page
    results['upstreamRejectedReplayBlocked'] = True
    v._admin_json('DELETE', prefix + '/users/' + users[0]['id'], expected=(204,))
    no_user()
    results['fixtureUserRemoved'] = True
    return results
