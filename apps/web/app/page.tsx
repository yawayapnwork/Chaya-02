import Link from "next/link";

export default function Home() {
  return (
    <main className="mx-auto max-w-2xl p-8">
      <h1 className="text-3xl font-semibold">Chaya 02</h1>
      <p className="mt-2 text-zinc-600">Digital twin platform for physical venues.</p>
      <Link className="mt-6 inline-block underline" href="/status">
        System status
      </Link>
    </main>
  );
}
