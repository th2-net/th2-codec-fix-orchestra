# FIX Orchestra Codec v0.2.1

FIX Orchestra codec uses [th2-codec](https://github.com/th2-net/th2-codec) as a core part.
Please, read more about core part functionality [here](https://github.com/th2-net/th2-codec/blob/master/README.md).

This component can encode and decode FIX messages using FIX Orchestra schema.
It also performs validation according to FIX Orchestra schema during encoding and decoding.
FIX Orchestra is a format for machine-readable rules of engagement between counterparties.
Unlike other formats for describing messages in FIX protocol (e.g. QuickFIXJ) it allows you to define the additional information such as:
+ Message structure by each scenario, implemented as an extension of FIX Repository
+ Accepted values of enumerations by message scenario
+ Express a condition such as for a conditionally required field using a Domain Specific Language (DSL)

You can read more about the FIX Orchestra [here](https://github.com/FIXTradingCommunity/fix-orchestra-spec/blob/master/v1-0-STANDARD/orchestra_spec.md).

Currently supported FIX Orchestra features:
+ uses the correct structure of message that correspond to specified scenario (or to the default one if it is not set)
  ```xml
  <fixr:messages>
    <fixr:message name="ExecutionReport" id="9" msgType="8" category="SingleGeneralOrderHandling" section="Trade" added="FIX.2.7" abbrName="ExecRpt" flow="Executions">
      <!--fields for scenario 'base'-->
    </fixr:message>
    <fixr:message name="ExecutionReport" id="9" msgType="8" scenario="expired" flow="Executions">
      <!--fields for scenario 'expired'-->
    </fixr:message>
  </fixr:messages>
  ```
  FIX Orchestra allows you to have different structure description for same message depending on the scenario of usage.
  You can change the set of tags, their order, change which tags are required for with scenario.
+ validation of conditionally required fields in message based on the rules that are specified for the field in message
  ```xml
  <fixr:fieldRef id="99" added="FIX.2.7">
    <fixr:rule name="StopOrderRequiresStopPx" presence="required">
      <fixr:when>OrdType == ^Stop || OrdType == ^StopLimit</fixr:when>
    </fixr:rule>
    <fixr:annotation>
      <fixr:documentation>
         Required for OrdType = "Stop" or OrdType = "Stop limit".
      </fixr:documentation>
    </fixr:annotation>
  </fixr:fieldRef>
  ```
  In FIX Orchestra you can add conditions for field to describe cases where this field is required or must not be set at all.
  It is done by the FIX Orchestra DSL that gives you access to the fields of current message and allows you to write conditions for field presence. 

The encoding and decoding can work in two modes:
+ **strict mode** when all errors reported during validation by FIX Orchestra schema will cause decoding/encoding error and the message won't go further.
The error event will be attached to your script if `parentEventId` is specified in the message.
+ **warning mode** when validation errors will be reported as a warning event (it will be attached to script if `parentEventId` is specified in the message).
The message itself will be passed further in components chain (unlike the **strict mode** does)

The mode can be switched by the corresponding parameters in the codec configuration: _**encodeErrorAsWaring**_ / _**decodeErrorAsWaring**_

## Configuration

This block contains description for all parameters that are specific for the **th2-codec-fix-orchestra**.
The list of common parameters and the general format for the configuration you can find [here](https://github.com/th2-net/th2-codec/blob/master/README.md#codec-settings).

+ **defaultScenario** - default scenario for message validation (`base` by default).
  You can also specify the scenario for a particular message by adding property `th2.codec.orchestra.scenario` with scenario name. 
+ **encodeErrorAsWaring** - message validation errors during encoding will be reported as warnings (`false` by default)
+ **decodeErrorAsWaring** - message validation errors during decoding will be reported as warnings (`false` by default)
+ **inlineComponents** - if `true` the _component_ blocks in the message will be added as flatten fields.
  Otherwise, the sub-message with component's name will be created and all component fields will be added to that sub-messages (`false` by default) 
+ **cacheSize** - cache size for the most commonly used properties such as FieldDatatype, groups, components etc. (`500` by default)

## Protocol

This codec works with parsed messages that should be decoded in `FIX` format and raw messages in `FIX` format that should be decoded in parsed messages.

## Deployment via `infra-mgr`

Here's an example of custom resource that is required for [infra-mgr](https://github.com/th2-net/th2-infra-mgr) to deploy this component.
_Custom resource_ for kubernetes is a common way to deploy components in th2.
You can find more information about th2 components deployment in the [th2 wiki](https://github.com/th2-net/th2-documentation/wiki).

```yaml
apiVersion: th2.exactpro.com/v1
kind: Th2Box
metadata:
  name: codec-fix-orchestra
spec:
  image-name: ghcr.io/th2-net/th2-codec-fix-orchestra
  image-version: 0.1.0
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


## Changelog

### v0.2.1

#### Fixed:

* Non-existent fields (messageScope returns fields, even if the message do not contain these fields, instead of returning null)

### v0.2.0

#### Changed:

* Exceptions and error events were made more readable (now they contain msgType, tags and scenario of the error message)

#### Added:

* Caching of properties (such as Groups, components etc.)
