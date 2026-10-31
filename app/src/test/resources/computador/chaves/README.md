Chaves FALSAS geradas só para os testes (`ssh-keygen`, comentário `teste-falso`), nunca usadas em servidor algum.
Passphrase das chaves `*-frase`: `frase-falsa-123`. Cada `.pub` é a saída de referência do `ssh-keygen -y`.
`rsa1024` e `dsa*` existem para provar a recusa; `*-pem` e `ed25519-pkcs8` cobrem PKCS#1/SEC1/PKCS#8.
