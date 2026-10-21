"""Locale selection through real CAPTCHA, login and registration HTTP flows.

Used only in the disposable TLS fixture. No provider endpoints are overridden.
"""
import html
import http.cookiejar
import json
import re
import urllib.error
import urllib.parse
import urllib.request
import uuid

from configure_and_verify import AcceptanceVerifier, PROTECTED_REALM, CLIENT_ID


def verify(base_url, theme=None):
    assert urllib.parse.urlparse(base_url).hostname in ('127.0.0.1', 'localhost')
    v = AcceptanceVerifier(base_url)
    v.refresh_admin_token()
    settings = {'internationalizationEnabled': True, 'supportedLocales': ['en', 'zh-CN'], 'defaultLocale': 'en'}
    if theme:
        settings['loginTheme'] = theme
    v._admin_json('PUT', '/admin/realms/' + PROTECTED_REALM, settings, expected=(204,))
    results = {}
    for flow, expected_page in [('auth', 'login'), ('registrations', 'register')]:
        for selected, other in [('en', 'zh-CN'), ('zh-CN', 'en')]:
            cookies = http.cookiejar.CookieJar()
            opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))

            def request(url, data=None):
                # Only this loopback HTTP fixture needs urllib's Secure-cookie workaround.
                for cookie in cookies:
                    cookie.secure = False
                req = urllib.request.Request(url, None if data is None else urllib.parse.urlencode(data).encode(),
                                             headers={'Accept-Language': other})
                try:
                    res = opener.open(req, timeout=15)
                except urllib.error.HTTPError as error:
                    res = error
                with res:
                    assert res.status == 200
                    return res.read().decode()

            def challenge(page, language):
                if theme:
                    assert re.search(r'"pageId"\s*:\s*"tencent-captcha(?:\.ftl)?"', page)
                    assert re.search(r'"captchaLanguage"\s*:\s*"' + re.escape(language) + '"', page)
                    match = re.search(r'"loginAction"\s*:\s*"([^"\n]+)"', page)
                    return html.unescape(json.loads('"' + match[1] + '"'))
                assert f'data-language-tag="{language}"' in page, (flow, language, re.findall(r'data-language-tag="([^"]+)"', page))
                assert ('Security verification' if language == 'en' else '安全验证') in page
                return html.unescape(re.search(r'data-login-action="([^"]+)"', page)[1])

            query = urllib.parse.urlencode({'client_id': CLIENT_ID, 'redirect_uri': base_url + '/callback',
                                            'response_type': 'code', 'scope': 'openid',
                                            'kc_locale': selected, 'ui_locales': selected})
            url = base_url + '/realms/' + PROTECTED_REALM + '/protocol/openid-connect/' + flow + '?' + query
            page = request(url)
            action = challenge(page, selected)
            # A failed proof must retain both the selected locale and the server gate.
            page = request(action, {})
            action = challenge(page, selected)
            expected_error = ('Verification was not accepted' if selected == 'en' else '验证未通过')
            assert expected_error in page
            # Follow Keycloak's own locale switch link, preserving the authentication session.
            links = [html.unescape(link) for link in re.findall(r'(?:href=|value=|"url"\s*:)\s*"([^"]+)"', page)]
            switch = next((link for link in links if 'kc_locale=' + other in link), None)
            assert switch is not None, 'locale switch link missing'
            if theme:
                switch = json.loads('"' + switch + '"')
            page = request(urllib.parse.urljoin(base_url, switch))
            action = challenge(page, other)
            page = request(action, {'ticket': 'integration-valid-' + uuid.uuid4().hex,
                                    'randstr': 'integration-randstr'})
            if theme:
                assert re.search(r'"pageId"\s*:\s*"' + expected_page + r'(?:\.ftl)?"', page)
                assert re.search(r'"currentLanguageTag"\s*:\s*"' + other + '"', page)
            else:
                assert ('id="kc-form-login"' if expected_page == 'login' else 'id="kc-register-form"') in page
                assert f'lang="{other}"' in page
            results[flow + '-' + selected + '-switch-and-continuation'] = True
    return results
