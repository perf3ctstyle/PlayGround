# Как это работает

Есть 2 контейнера:
1. vault-init-agent (init container)
2. nginx (main container)

vault-init-agent отрабатывает во время запуска поды и выключается, при этом он:
* использует Service Account token для обращения к Vault
* Service Account token не маунтится автоматически в поду, а пробрасывается через Projected Volume исключительно в init container (смотреть строки 18-23 в [манифесте приложения](03-app.yaml))

