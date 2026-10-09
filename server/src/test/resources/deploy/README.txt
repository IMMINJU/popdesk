Test resources of server/src/test/java/io/popdesk/deploy (see the README.md there).

mail-tls/mail-server.p12  key and self-signed certificate for the host "mail" (also localhost,
                          127.0.0.1), PKCS12, password popdesk-test, valid for 100 years.
mail-tls/mail-trust.p12   that certificate only, as a trust store, password popdesk-test.
smtps.compose.yml         GreenMail with implicit TLS on 465, for ImplicitTlsMailTest.
upgrade/v999/             a migration that breaks the sample data, for UpgradeHarnessTest.
upgrade/v999.compose.yml  mounts it as an extra Flyway location.

Made with:
  keytool -genkeypair -alias mail -keyalg RSA -keysize 2048 -validity 36500 -dname CN=mail \
    -ext SAN=dns:mail,dns:localhost,ip:127.0.0.1 -storetype PKCS12 -keystore mail-server.p12 \
    -storepass popdesk-test -keypass popdesk-test
  keytool -exportcert -alias mail -keystore mail-server.p12 -storepass popdesk-test -rfc -file mail.crt
  keytool -importcert -noprompt -alias mail -file mail.crt -storetype PKCS12 \
    -keystore mail-trust.p12 -storepass popdesk-test
