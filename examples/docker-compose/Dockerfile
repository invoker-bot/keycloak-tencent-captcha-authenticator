FROM quay.io/keycloak/keycloak:26.7.0 AS builder

COPY --chown=1000:0 target/keycloak-tencent-captcha-authenticator-0.1.0.jar /opt/keycloak/providers/keycloak-tencent-captcha-authenticator.jar
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.7.0

COPY --from=builder /opt/keycloak/ /opt/keycloak/

USER 1000
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
