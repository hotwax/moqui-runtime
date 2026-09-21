# README -- Custom Changes Applied to Vendor Branch

This repository contains enhancements and fixes applied on top of the upstream Moqui Runtime.

## Summary of Enhancements

### [AutoScreenCreatedStamp.patch](./AutoScreenCreatedStamp.patch)
Added `createdStamp` handling to AutoScreen tools forms.

- **AutoEditMaster.xml**: Displays `createdStamp` alongside `lastUpdatedStamp` in the entity detail view.
- **AutoEditDetail.xml**: Hides `createdStamp` in the create entity dialog so users don't manually edit the automatic creation timestamp.
- **AutoFind.xml**: Hides `createdStamp` in the create value dialog form.

**Benefit**: Integrates the automatic `createdStamp` field with the Moqui Tools AutoScreen interface, displaying it when viewing records and hiding it during record creation.
