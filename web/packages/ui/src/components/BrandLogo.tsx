/**
 * The branding logo of `AppShell` and `AuthBrand`: two `<img alt="">` (light, dark) and CSS shows
 * the one of the current theme by the token conditions (`data-theme`, else
 * `prefers-color-scheme`). One `<img>` when both URLs are the same. The display name always sits
 * beside it, so the image says nothing more (S9-AC-25).
 */
export function BrandLogo(props: { logo: { light: string; dark: string } }) {
  const { light, dark } = props.logo;
  if (light === dark) return <img className="pd-brand-logo" src={light} alt="" />;
  return (
    <>
      <img className="pd-brand-logo" data-brand-theme="light" src={light} alt="" />
      <img className="pd-brand-logo" data-brand-theme="dark" src={dark} alt="" />
    </>
  );
}
