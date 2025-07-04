# Как это работает

Есть 2 контейнера:
1. vault-init-agent (init container)
2. nginx (main container)

vault-init-agent отрабатывает во время запуска поды и выключается, при этом он:
* использует Service Account token для обращения к Vault
* есть отдельный [Service Account](02-sa.yaml), для которого мы настраиваем kubernetes auth в Vault
* Service Account token не маунтится автоматически в поду, а пробрасывается через Projected Volume исключительно в init container (смотреть строки [18-25](03-app.yaml#L18) и [48-50](03-app.yaml#L48) в манифесте приложения)
* конфиг для него задается в виде [ConfigMap](01-cm.yaml), при этом для него есть [документация](https://developer.hashicorp.com/vault/docs/agent-and-proxy/agent#configuration-file-options)

Логика работы агента представлена [на схеме](https://developer.hashicorp.com/vault/tutorials/kubernetes-introduction/agent-kubernetes#challenge), но если ее упростить:
1. агент запускается
2. идет с SA token в Vault
3. Vault берет свой SA token и идет с ним и токеном агента в kube-api-server, дергая TokenReview API, которая ему говорит, валиден токен агента или нет
4. kube-api-server отвечает, что да
5. Vault возвращает свой собственный токен агенту
6. агент идет с токеном из Vault в Vault за секретами
7. форматирует их нужным образом, заданным в ConfigMap агента
8. завершает свою работу

Дальше в ход вступает приложение, читает подложенный агентом файл и удаляет его
В результате приложение (или злодей, получивший доступ к поде) не может сходить в вольт, поскольку на момент работы приложения в нем нет ни секретов, ни кредов, чтобы их получить
