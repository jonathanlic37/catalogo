# Contratos entre Consumer y Producer

Fuente única de los contratos HTTP entre las dos APIs. Ambos módulos Maven los cargan como
recursos de test (`<testResources>` apunta a esta carpeta), de modo que no pueden divergir:

| Fichero | Productor del JSON | Lo verifica |
|---|---|---|
| `webhook-created.json` | Consumer (cuerpo del webhook) | Consumer: el payload que envía tiene exactamente estas claves. Producer: acepta este cuerpo y crea el ítem. |
| `item-response.json` | Producer (estado canónico) | Producer: su respuesta tiene exactamente estas claves. Consumer: lo deserializa a su modelo sin perder campos. |

Cambiar un contrato obliga a que ambos lados pasen sus tests.
