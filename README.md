# FIX Orchestra Codec v0.0.1

This microservice can encode, decode and validate FIX messages using FIX Orchestra schema

## Configuration

+ *defaultScenario* - default scenario for message validation (`base` by default)

## Protocol

This codec processes parsed messages with `FIX` protocol in metadata field

## Deployment via `infra-mgr`

Here's an example of `infra-mgr` config required to deploy this service

```yaml
apiVersion: th2.exactpro.com/v1
kind: Th2Box
metadata:
  name: codec-fix-orchestra
spec:
  image-name: ghcr.io/th2-net/th2-codec-fix-orchestra
  image-version: 0.0.1
  custom-config:
    codecSettings:
      defaultScenario: base
  type: th2-codec
  pins:
    # encoder
    - name: in_codec_encode
      connection-type: mq
      attributes:
        - encoder_in
        - subscribe
    - name: out_codec_encode
      connection-type: mq
      attributes:
        - encoder_out
        - publish
    # decoder
    - name: in_codec_decode
      connection-type: mq
      attributes:
        - decoder_in
        - subscribe
    - name: out_codec_decode
      connection-type: mq
      attributes:
        - decoder_out
        - publish
    # encoder general (technical)
    - name: in_codec_general_encode
      connection-type: mq
      attributes:
        - general_encoder_in
        - subscribe
    - name: out_codec_general_encode
      connection-type: mq
      attributes:
        - general_encoder_out
        - publish
    # decoder general (technical)
    - name: in_codec_general_decode
      connection-type: mq
      attributes:
        - general_decoder_in
        - subscribe
    - name: out_codec_general_decode
      connection-type: mq
      attributes:
        - general_decoder_out
        - publish
  extended-settings:
    service:
      enabled: false
```
