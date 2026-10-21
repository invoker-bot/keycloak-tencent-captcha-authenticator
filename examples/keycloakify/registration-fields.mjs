export function requiredRegistrationContext(kcContext) {
    if (kcContext.pageId !== "register.ftl") return kcContext;
    return {
        ...kcContext,
        profile: {
            ...kcContext.profile,
            attributesByName: Object.fromEntries(
                Object.entries(kcContext.profile.attributesByName)
                    .filter(([, attribute]) => attribute.required === true)
            )
        }
    };
}
