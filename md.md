### Additional outbound event: `FOLIO-GENERATED`
Besides the `folio record` produced to T-HUB, Folicure also produces a domain event to the `FOLIO-GENERATED` topic for every successfully processed listener message that resolves a folio.
This means a successful listener message can produce:
- `T-HUB.NEW-RECORD.PAYMENTS.BASE.FOLIO`
- `FOLIO-GENERATED`
### Payload
```clojure
{:customer-id    {:schema s/Uuid :required true}
 :customer-folio {:schema s/Str :required true}
 :entity-id      {:schema s/Uuid :required true}
 :entity-type    {:schema s/Keyword :required true}
 :product-type   {:schema s/Keyword :required true}
 :operation-type {:schema s/Keyword :required true}
 :operation-id   {:schema s/Str :required true}
 :generated-at   {:schema Instant :required true}}```
